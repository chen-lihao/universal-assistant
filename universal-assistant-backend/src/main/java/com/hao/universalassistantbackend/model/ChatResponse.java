package com.hao.universalassistantbackend.model;

import java.util.List;

public record ChatResponse(
        String answer,
        boolean realtimeSearchUsed,
        boolean modelAvailable,
        String model,
        List<SearchResult> sources
) {
}
