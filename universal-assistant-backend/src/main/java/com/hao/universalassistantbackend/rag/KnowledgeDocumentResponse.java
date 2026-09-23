package com.hao.universalassistantbackend.rag;

import java.time.Instant;
import java.util.UUID;

public record KnowledgeDocumentResponse(
        UUID id,
        UUID knowledgeBaseId,
        String title,
        String sourceUri,
        String status,
        int chunkCount,
        Instant createdAt,
        Instant updatedAt
) {
}
