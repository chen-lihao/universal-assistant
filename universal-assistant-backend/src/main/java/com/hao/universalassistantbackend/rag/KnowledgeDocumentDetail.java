package com.hao.universalassistantbackend.rag;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record KnowledgeDocumentDetail(
        UUID id,
        UUID knowledgeBaseId,
        String title,
        String sourceUri,
        String content,
        Map<String, Object> metadata,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}
