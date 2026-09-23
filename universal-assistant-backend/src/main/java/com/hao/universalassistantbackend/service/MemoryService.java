package com.hao.universalassistantbackend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hao.universalassistantbackend.model.MemoryHit;
import com.hao.universalassistantbackend.rag.SemanticEmbeddingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class MemoryService {

    private static final int EMBEDDING_DIMENSIONS = 384;
    private static final int MAX_MEMORY_CONTENT = 2200;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SemanticEmbeddingService semanticEmbeddingService;
    private final boolean enabled;
    private final int retrieveLimit;

    public MemoryService(JdbcTemplate jdbcTemplate,
                         ObjectMapper objectMapper,
                         SemanticEmbeddingService semanticEmbeddingService,
                         @Value("${assistant.memory.enabled:true}") boolean enabled,
                         @Value("${assistant.memory.retrieve-limit:5}") int retrieveLimit) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.semanticEmbeddingService = semanticEmbeddingService;
        this.enabled = enabled;
        this.retrieveLimit = Math.max(1, retrieveLimit);
    }

    public List<MemoryHit> retrieveRelevantMemories(String query, UUID conversationId) {
        if (!enabled || !StringUtils.hasText(query) || conversationId == null) {
            return List.of();
        }

        List<MemoryHit> semanticHits = semanticEmbeddingService.embed(query)
                .map(semanticEmbeddingService::toVectorLiteral)
                .map(vector -> retrieveSemantic(vector, conversationId))
                .orElseGet(List::of);
        if (!semanticHits.isEmpty()) {
            return semanticHits;
        }

        String vector = toVectorLiteral(embed(query));
        try {
            return jdbcTemplate.query(
                    """
                            SELECT id, content, GREATEST(0, 1 - (embedding <=> ?::vector)) AS score
                            FROM memory_items
                            WHERE invalidated_at IS NULL
                              AND (conversation_id = ? OR conversation_id IS NULL)
                            ORDER BY embedding <=> ?::vector
                            LIMIT ?
                            """,
                    ps -> {
                        ps.setString(1, vector);
                        ps.setObject(2, conversationId);
                        ps.setString(3, vector);
                        ps.setInt(4, retrieveLimit);
                    },
                    (rs, rowNum) -> toMemoryHit(rs)
            );
        } catch (DataAccessException ex) {
            return List.of();
        }
    }

    public void rememberConversationTurn(UUID conversationId,
                                         UUID assistantMessageId,
                                         String userMessage,
                                         String assistantAnswer,
                                         String model,
                                         int sourceCount) {
        if (!enabled || conversationId == null || !StringUtils.hasText(userMessage) || !StringUtils.hasText(assistantAnswer)) {
            return;
        }

        String content = trim("""
                用户：%s
                助手：%s
                """.formatted(userMessage.trim(), assistantAnswer.trim()), MAX_MEMORY_CONTENT);
        String metadata = writeJson(Map.of(
                "model", model == null ? "" : model,
                "sourceCount", sourceCount
        ));
        String vector = toVectorLiteral(embed(content));
        String semanticVector = semanticEmbeddingService.embed(content)
                .map(semanticEmbeddingService::toVectorLiteral)
                .orElse(null);

        try {
            jdbcTemplate.update(
                    """
                            INSERT INTO memory_items(
                                id, conversation_id, message_id, kind, content, metadata_json, embedding,
                                semantic_embedding, embedding_model, created_at
                            )
                            VALUES (?, ?, ?, ?, ?, ?, ?::vector, ?::vector, ?, ?)
                            """,
                    UUID.randomUUID(),
                    conversationId,
                    assistantMessageId,
                    "conversation_turn",
                    content,
                    metadata,
                    vector,
                    semanticVector,
                    semanticVector == null ? null : semanticEmbeddingService.modelName(),
                    Instant.now()
            );
        } catch (DataAccessException ignored) {
            // Memory is an enhancement. Chat should still work if pgvector is unavailable.
        }
    }

    private List<MemoryHit> retrieveSemantic(String vector, UUID conversationId) {
        try {
            return jdbcTemplate.query(
                    """
                            SELECT id, content, GREATEST(0, 1 - (semantic_embedding <=> ?::vector)) AS score
                            FROM memory_items
                            WHERE invalidated_at IS NULL
                              AND semantic_embedding IS NOT NULL
                              AND (conversation_id = ? OR conversation_id IS NULL)
                            ORDER BY semantic_embedding <=> ?::vector
                            LIMIT ?
                            """,
                    ps -> {
                        ps.setString(1, vector);
                        ps.setObject(2, conversationId);
                        ps.setString(3, vector);
                        ps.setInt(4, retrieveLimit);
                    },
                    (rs, rowNum) -> toMemoryHit(rs)
            );
        } catch (DataAccessException ex) {
            return List.of();
        }
    }

    public void invalidateAfter(UUID conversationId, Instant createdAt) {
        if (conversationId == null || createdAt == null) {
            return;
        }

        try {
            jdbcTemplate.update(
                    """
                            UPDATE memory_items
                            SET invalidated_at = ?
                            WHERE conversation_id = ?
                              AND invalidated_at IS NULL
                              AND created_at > ?
                            """,
                    Instant.now(),
                    conversationId,
                    createdAt
            );
        } catch (DataAccessException ignored) {
            // Memory invalidation should not block editing conversation history.
        }
    }

    private MemoryHit toMemoryHit(ResultSet rs) throws SQLException {
        return new MemoryHit(
                rs.getObject("id", UUID.class),
                rs.getString("content"),
                rs.getDouble("score")
        );
    }

    private double[] embed(String text) {
        double[] vector = new double[EMBEDDING_DIMENSIONS];
        for (String token : tokenize(text)) {
            String digest = sha256(token);
            int bucket = Math.floorMod(digest.substring(0, 8).hashCode(), EMBEDDING_DIMENSIONS);
            int sign = (Integer.parseUnsignedInt(digest.substring(8, 10), 16) & 1) == 0 ? 1 : -1;
            vector[bucket] += sign;
        }

        double norm = 0;
        for (double value : vector) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        if (norm == 0) {
            return vector;
        }

        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / norm;
        }
        return vector;
    }

    private List<String> tokenize(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        StringBuilder ascii = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                ascii.append(ch);
                continue;
            }

            flushAsciiToken(tokens, ascii);
            if (!Character.isWhitespace(ch) && !Character.isISOControl(ch)) {
                tokens.add(String.valueOf(ch));
                if (i + 1 < normalized.length()) {
                    char next = normalized.charAt(i + 1);
                    if (!Character.isLetterOrDigit(next) && !Character.isWhitespace(next) && !Character.isISOControl(next)) {
                        tokens.add("" + ch + next);
                    }
                }
            }
        }
        flushAsciiToken(tokens, ascii);
        return tokens;
    }

    private void flushAsciiToken(List<String> tokens, StringBuilder ascii) {
        if (ascii.isEmpty()) {
            return;
        }

        String token = ascii.toString();
        tokens.add(token);
        for (int i = 0; i + 3 <= token.length(); i++) {
            tokens.add(token.substring(i, i + 3));
        }
        ascii.setLength(0);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private String toVectorLiteral(double[] vector) {
        StringBuilder literal = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append(String.format(Locale.ROOT, "%.6f", vector[i]));
        }
        return literal.append(']').toString();
    }

    private String writeJson(Map<String, ?> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private String trim(String content, int maxLength) {
        if (!StringUtils.hasText(content) || content.length() <= maxLength) {
            return content == null ? "" : content;
        }
        return content.substring(0, maxLength - 1) + "…";
    }
}
