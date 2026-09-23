package com.hao.universalassistantbackend.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class KnowledgeService {

    public static final UUID DEFAULT_KNOWLEDGE_BASE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final int MAX_DOCUMENT_CHARS = 2_000_000;
    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TextChunker textChunker;
    private final SemanticEmbeddingService embeddingService;
    private final boolean enabled;
    private final int retrieveLimit;

    public KnowledgeService(JdbcTemplate jdbcTemplate,
                            ObjectMapper objectMapper,
                            TextChunker textChunker,
                            SemanticEmbeddingService embeddingService,
                            @Value("${assistant.rag.enabled:true}") boolean enabled,
                            @Value("${assistant.rag.retrieve-limit:5}") int retrieveLimit) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.textChunker = textChunker;
        this.embeddingService = embeddingService;
        this.enabled = enabled;
        this.retrieveLimit = Math.max(1, retrieveLimit);
    }

    @Transactional
    public KnowledgeDocumentResponse ingest(KnowledgeDocumentRequest request) {
        if (!enabled) {
            throw new IllegalStateException("RAG 知识库未启用。");
        }
        if (request == null || !StringUtils.hasText(request.content())) {
            throw new IllegalArgumentException("文档内容不能为空。");
        }
        if (request.content().length() > MAX_DOCUMENT_CHARS) {
            throw new IllegalArgumentException("单个文档不能超过 " + MAX_DOCUMENT_CHARS + " 个字符。");
        }

        UUID knowledgeBaseId = parseUuid(request.knowledgeBaseId()).orElse(DEFAULT_KNOWLEDGE_BASE_ID);
        String title = StringUtils.hasText(request.title()) ? request.title().trim() : "未命名文档";
        String content = request.content().trim();
        String contentHash = sha256(content);
        Optional<KnowledgeDocumentResponse> existing = findByHash(knowledgeBaseId, contentHash);
        if (existing.isPresent()) {
            return existing.get();
        }

        List<String> chunks = textChunker.split(content);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("文档没有可索引的文本内容。");
        }

        UUID documentId = UUID.randomUUID();
        Instant now = Instant.now();
        String metadataJson = writeJson(request.metadata());
        jdbcTemplate.update(
                """
                        INSERT INTO knowledge_documents(
                            id, knowledge_base_id, title, source_uri, full_content, content_hash,
                            metadata_json, status, chunk_count, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, 'processing', 0, ?, ?)
                        """,
                documentId,
                knowledgeBaseId,
                title,
                blankToNull(request.sourceUri()),
                content,
                contentHash,
                metadataJson,
                now,
                now
        );

        List<float[]> embeddings = embeddingService.embedAll(chunks);
        for (int index = 0; index < chunks.size(); index++) {
            float[] embedding = embeddings.size() == chunks.size() ? embeddings.get(index) : null;
            insertChunk(documentId, index, chunks.get(index), metadataJson, embedding, now);
        }

        jdbcTemplate.update(
                "UPDATE knowledge_documents SET status = 'ready', chunk_count = ?, updated_at = ? WHERE id = ?",
                chunks.size(), Instant.now(), documentId
        );
        return getDocument(documentId)
                .map(detail -> new KnowledgeDocumentResponse(
                        detail.id(), detail.knowledgeBaseId(), detail.title(), detail.sourceUri(), detail.status(), chunks.size(), detail.createdAt(), detail.updatedAt()))
                .orElseThrow();
    }

    public List<KnowledgeHit> search(String query) {
        if (!enabled || !StringUtils.hasText(query) || !hasReadyDocuments()) {
            return List.of();
        }

        int candidateLimit = Math.max(retrieveLimit * 3, 10);
        List<RankedHit> semantic = embeddingService.embed(query)
                .map(vector -> semanticSearch(embeddingService.toVectorLiteral(vector), candidateLimit))
                .orElseGet(List::of);
        List<RankedHit> lexical = lexicalSearch(query, candidateLimit);
        return reciprocalRankFusion(semantic, lexical, retrieveLimit);
    }

    public List<KnowledgeDocumentResponse> listDocuments() {
        if (!enabled) {
            return List.of();
        }
        try {
            return jdbcTemplate.query(
                    """
                            SELECT id, knowledge_base_id, title, source_uri, status, chunk_count, created_at, updated_at
                            FROM knowledge_documents
                            ORDER BY updated_at DESC
                            LIMIT 100
                            """,
                    (rs, rowNum) -> toDocumentResponse(rs)
            );
        } catch (DataAccessException ex) {
            return List.of();
        }
    }

    public Optional<KnowledgeDocumentDetail> getDocument(UUID documentId) {
        if (!enabled || documentId == null) {
            return Optional.empty();
        }
        try {
            return jdbcTemplate.query(
                    """
                            SELECT id, knowledge_base_id, title, source_uri, full_content, metadata_json,
                                   status, created_at, updated_at
                            FROM knowledge_documents
                            WHERE id = ?
                            """,
                    ps -> ps.setObject(1, documentId),
                    rs -> rs.next() ? Optional.of(toDocumentDetail(rs)) : Optional.empty()
            );
        } catch (DataAccessException ex) {
            return Optional.empty();
        }
    }

    private boolean hasReadyDocuments() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM knowledge_documents WHERE status = 'ready'",
                    Integer.class
            );
            return count != null && count > 0;
        } catch (DataAccessException ex) {
            return false;
        }
    }

    private Optional<KnowledgeDocumentResponse> findByHash(UUID knowledgeBaseId, String contentHash) {
        return jdbcTemplate.query(
                """
                        SELECT id, knowledge_base_id, title, source_uri, status, chunk_count, created_at, updated_at
                        FROM knowledge_documents
                        WHERE knowledge_base_id = ? AND content_hash = ? AND status = 'ready'
                        LIMIT 1
                        """,
                ps -> {
                    ps.setObject(1, knowledgeBaseId);
                    ps.setString(2, contentHash);
                },
                rs -> rs.next() ? Optional.of(toDocumentResponse(rs)) : Optional.empty()
        );
    }

    private void insertChunk(UUID documentId,
                             int chunkIndex,
                             String content,
                             String metadataJson,
                             float[] embedding,
                             Instant createdAt) {
        if (embedding == null) {
            jdbcTemplate.update(
                    """
                            INSERT INTO knowledge_chunks(
                                id, document_id, chunk_index, content, metadata_json, embedding_model, created_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?)
                            """,
                    UUID.randomUUID(), documentId, chunkIndex, content, metadataJson, null, createdAt
            );
            return;
        }

        jdbcTemplate.update(
                """
                        INSERT INTO knowledge_chunks(
                            id, document_id, chunk_index, content, metadata_json, embedding, embedding_model, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?::vector, ?, ?)
                        """,
                UUID.randomUUID(), documentId, chunkIndex, content, metadataJson,
                embeddingService.toVectorLiteral(embedding), embeddingService.modelName(), createdAt
        );
    }

    private List<RankedHit> semanticSearch(String vector, int limit) {
        try {
            return jdbcTemplate.query(
                    """
                            SELECT c.id AS chunk_id, d.id AS document_id, d.title, d.source_uri, c.content,
                                   GREATEST(0, 1 - (c.embedding <=> ?::vector)) AS score
                            FROM knowledge_chunks c
                            JOIN knowledge_documents d ON d.id = c.document_id
                            JOIN knowledge_bases b ON b.id = d.knowledge_base_id
                            WHERE c.embedding IS NOT NULL AND d.status = 'ready' AND b.enabled = TRUE
                            ORDER BY c.embedding <=> ?::vector
                            LIMIT ?
                            """,
                    ps -> {
                        ps.setString(1, vector);
                        ps.setString(2, vector);
                        ps.setInt(3, limit);
                    },
                    (rs, rowNum) -> new RankedHit(toKnowledgeHit(rs), rowNum + 1)
            );
        } catch (DataAccessException ex) {
            return List.of();
        }
    }

    private List<RankedHit> lexicalSearch(String query, int limit) {
        try {
            return jdbcTemplate.query(
                    """
                            SELECT c.id AS chunk_id, d.id AS document_id, d.title, d.source_uri, c.content,
                                   GREATEST(
                                       similarity(c.content, ?),
                                       ts_rank(c.search_vector, plainto_tsquery('simple', ?))
                                   ) AS score
                            FROM knowledge_chunks c
                            JOIN knowledge_documents d ON d.id = c.document_id
                            JOIN knowledge_bases b ON b.id = d.knowledge_base_id
                            WHERE d.status = 'ready' AND b.enabled = TRUE
                              AND (c.content % ? OR c.search_vector @@ plainto_tsquery('simple', ?))
                            ORDER BY score DESC
                            LIMIT ?
                            """,
                    ps -> {
                        ps.setString(1, query);
                        ps.setString(2, query);
                        ps.setString(3, query);
                        ps.setString(4, query);
                        ps.setInt(5, limit);
                    },
                    (rs, rowNum) -> new RankedHit(toKnowledgeHit(rs), rowNum + 1)
            );
        } catch (DataAccessException ex) {
            return List.of();
        }
    }

    private List<KnowledgeHit> reciprocalRankFusion(List<RankedHit> semantic,
                                                     List<RankedHit> lexical,
                                                     int limit) {
        Map<UUID, FusedHit> fused = new LinkedHashMap<>();
        mergeRanks(fused, semantic, 0.7);
        mergeRanks(fused, lexical, 0.3);
        return fused.values().stream()
                .sorted(Comparator.comparingDouble(FusedHit::score).reversed())
                .limit(limit)
                .map(item -> new KnowledgeHit(
                        item.hit().chunkId(), item.hit().documentId(), item.hit().title(), item.hit().sourceUri(),
                        item.hit().content(), item.score()))
                .toList();
    }

    private void mergeRanks(Map<UUID, FusedHit> fused, List<RankedHit> hits, double weight) {
        for (RankedHit ranked : hits) {
            double score = weight / (60.0 + ranked.rank());
            fused.compute(ranked.hit().chunkId(), (id, current) -> current == null
                    ? new FusedHit(ranked.hit(), score)
                    : new FusedHit(current.hit(), current.score() + score));
        }
    }

    private KnowledgeHit toKnowledgeHit(ResultSet rs) throws SQLException {
        return new KnowledgeHit(
                rs.getObject("chunk_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getString("title"),
                rs.getString("source_uri"),
                rs.getString("content"),
                rs.getDouble("score")
        );
    }

    private KnowledgeDocumentResponse toDocumentResponse(ResultSet rs) throws SQLException {
        return new KnowledgeDocumentResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("knowledge_base_id", UUID.class),
                rs.getString("title"),
                rs.getString("source_uri"),
                rs.getString("status"),
                rs.getInt("chunk_count"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private KnowledgeDocumentDetail toDocumentDetail(ResultSet rs) throws SQLException {
        return new KnowledgeDocumentDetail(
                rs.getObject("id", UUID.class),
                rs.getObject("knowledge_base_id", UUID.class),
                rs.getString("title"),
                rs.getString("source_uri"),
                rs.getString("full_content"),
                readMetadata(rs.getString("metadata_json")),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private Map<String, Object> readMetadata(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, METADATA_TYPE);
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private String writeJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private Optional<UUID> parseUuid(String value) {
        if (!StringUtils.hasText(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("knowledgeBaseId 格式不正确。");
        }
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256。", ex);
        }
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private record RankedHit(KnowledgeHit hit, int rank) {
    }

    private record FusedHit(KnowledgeHit hit, double score) {
    }
}
