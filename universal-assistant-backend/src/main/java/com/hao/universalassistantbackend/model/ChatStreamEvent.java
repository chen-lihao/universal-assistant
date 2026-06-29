package com.hao.universalassistantbackend.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatStreamEvent(
        String type,
        String phase,
        String message,
        String content,
        Boolean realtimeSearchUsed,
        Boolean modelAvailable,
        String model,
        List<SearchResult> sources,
        UUID conversationId,
        UUID messageId,
        UUID agentRunId,
        AgentStepResponse agentStep
) {

    public static ChatStreamEvent status(String phase, String message) {
        return new ChatStreamEvent("status", phase, message, null, null, null, null, null, null, null, null, null);
    }

    public static ChatStreamEvent meta(String model, boolean realtimeSearchUsed, boolean modelAvailable, List<SearchResult> sources) {
        return meta(model, realtimeSearchUsed, modelAvailable, sources, null, null, null);
    }

    public static ChatStreamEvent meta(String model,
                                       boolean realtimeSearchUsed,
                                       boolean modelAvailable,
                                       List<SearchResult> sources,
                                       UUID conversationId,
                                       UUID messageId,
                                       UUID agentRunId) {
        return new ChatStreamEvent("meta", null, null, null, realtimeSearchUsed, modelAvailable, model, sources, conversationId, messageId, agentRunId, null);
    }

    public static ChatStreamEvent delta(String content) {
        return new ChatStreamEvent("delta", null, null, content, null, null, null, null, null, null, null, null);
    }

    public static ChatStreamEvent agentStep(AgentStepResponse agentStep) {
        return new ChatStreamEvent("agent_step", null, null, null, null, null, null, null, null, null, agentStep.runId(), agentStep);
    }

    public static ChatStreamEvent done() {
        return new ChatStreamEvent("done", "done", "回答完毕", null, null, null, null, null, null, null, null, null);
    }

    public static ChatStreamEvent error(String message) {
        return new ChatStreamEvent("error", "error", message, null, null, false, null, null, null, null, null, null);
    }
}
