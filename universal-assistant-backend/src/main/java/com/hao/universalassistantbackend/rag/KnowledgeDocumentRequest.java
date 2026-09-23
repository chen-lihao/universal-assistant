package com.hao.universalassistantbackend.rag;

import java.util.Map;

public record KnowledgeDocumentRequest(
        String knowledgeBaseId,
        String title,
        String sourceUri,
        String content,
        Map<String, Object> metadata
) {
}
