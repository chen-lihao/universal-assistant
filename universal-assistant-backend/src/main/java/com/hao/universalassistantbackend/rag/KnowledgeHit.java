package com.hao.universalassistantbackend.rag;

import com.hao.universalassistantbackend.model.SearchResult;

import java.util.UUID;

public record KnowledgeHit(
        UUID chunkId,
        UUID documentId,
        String title,
        String sourceUri,
        String content,
        double score
) {
    public SearchResult toSearchResult() {
        String resolvedUri = sourceUri == null || sourceUri.isBlank()
                ? "http://localhost:8080/api/knowledge/documents/" + documentId
                : sourceUri;
        return new SearchResult(title, resolvedUri, content, "knowledge");
    }
}
