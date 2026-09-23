package com.hao.universalassistantbackend.rag;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.List;
import java.util.Optional;

@Service
public class SemanticEmbeddingService {

    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final boolean enabled;
    private final String apiKey;
    private final String modelName;
    private final int dimensions;

    public SemanticEmbeddingService(ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                    @Value("${assistant.rag.enabled:true}") boolean enabled,
                                    @Value("${spring.ai.dashscope.api-key:}") String apiKey,
                                    @Value("${spring.ai.dashscope.embedding.options.model:text-embedding-v3}") String modelName,
                                    @Value("${spring.ai.dashscope.embedding.options.dimensions:1024}") int dimensions) {
        this.embeddingModelProvider = embeddingModelProvider;
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.dimensions = dimensions;
    }

    public Optional<float[]> embed(String text) {
        if (!isAvailable() || !StringUtils.hasText(text)) {
            return Optional.empty();
        }

        try {
            float[] vector = embeddingModelProvider.getObject().embed(text);
            return vector != null && vector.length == dimensions ? Optional.of(vector) : Optional.empty();
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    public List<float[]> embedAll(List<String> texts) {
        if (!isAvailable() || texts == null || texts.isEmpty()) {
            return List.of();
        }
        try {
            List<float[]> vectors = embeddingModelProvider.getObject().embed(texts);
            if (vectors.size() != texts.size() || vectors.stream().anyMatch(vector -> vector == null || vector.length != dimensions)) {
                return List.of();
            }
            return vectors;
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    public boolean isAvailable() {
        return enabled
                && StringUtils.hasText(apiKey)
                && !"missing-api-key".equals(apiKey)
                && embeddingModelProvider.getIfAvailable() != null;
    }

    public String modelName() {
        return modelName;
    }

    public String toVectorLiteral(float[] vector) {
        StringBuilder literal = new StringBuilder("[");
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append(String.format(Locale.ROOT, "%.7f", vector[index]));
        }
        return literal.append(']').toString();
    }
}
