package com.hao.universalassistantbackend.model;

import java.util.List;
import java.util.UUID;

public record ChatResponse(
        String answer,
        boolean realtimeSearchUsed,
        boolean modelAvailable,
        String model,
        List<SearchResult> sources,
        UUID conversationId,
        UUID messageId,
        UUID agentRunId,
        List<AgentStepResponse> agentSteps
) {

    public ChatResponse(String answer,
                        boolean realtimeSearchUsed,
                        boolean modelAvailable,
                        String model,
                        List<SearchResult> sources) {
        this(answer, realtimeSearchUsed, modelAvailable, model, sources, null, null, null, List.of());
    }

    public ChatResponse(String answer,
                        boolean realtimeSearchUsed,
                        boolean modelAvailable,
                        String model,
                        List<SearchResult> sources,
                        UUID conversationId,
                        UUID messageId) {
        this(answer, realtimeSearchUsed, modelAvailable, model, sources, conversationId, messageId, null, List.of());
    }
}
