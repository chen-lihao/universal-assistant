package com.hao.universalassistantbackend.model;

import java.util.List;

public record CancelAgentRunRequest(
        String partialAnswer,
        String model,
        Boolean realtimeSearchUsed,
        Boolean modelAvailable,
        List<SearchResult> sources
) {
}
