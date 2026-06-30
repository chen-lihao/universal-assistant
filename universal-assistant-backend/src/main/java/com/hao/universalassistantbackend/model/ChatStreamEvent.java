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
        UUID userMessageId,
        UUID agentRunId,
        AgentStepResponse agentStep,
        String toolName,
        String toolInput,
        String reason,
        MessageBlock block,
        String blockId
) {

    public static ChatStreamEvent status(String phase, String message) {
        return new ChatStreamEvent("status", phase, message, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
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
        return meta(model, realtimeSearchUsed, modelAvailable, sources, conversationId, messageId, null, agentRunId);
    }

    public static ChatStreamEvent meta(String model,
                                       boolean realtimeSearchUsed,
                                       boolean modelAvailable,
                                       List<SearchResult> sources,
                                       UUID conversationId,
                                       UUID messageId,
                                       UUID userMessageId,
                                       UUID agentRunId) {
        return new ChatStreamEvent("meta", null, null, null, realtimeSearchUsed, modelAvailable, model, sources, conversationId, messageId, userMessageId, agentRunId, null, null, null, null, null, null);
    }

    public static ChatStreamEvent delta(String content) {
        return new ChatStreamEvent("delta", null, null, content, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static ChatStreamEvent blockStart(MessageBlock block) {
        return new ChatStreamEvent("block_start", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, block, block == null ? null : block.id());
    }

    public static ChatStreamEvent blockDelta(String blockId, String content) {
        return new ChatStreamEvent("block_delta", null, null, content, null, null, null, null, null, null, null, null, null, null, null, null, null, blockId);
    }

    public static ChatStreamEvent blockEnd(String blockId) {
        return new ChatStreamEvent("block_end", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, blockId);
    }

    public static ChatStreamEvent answerReset(String message) {
        return new ChatStreamEvent("answer_reset", "answering", message, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static ChatStreamEvent agentStep(AgentStepResponse agentStep) {
        return new ChatStreamEvent("agent_step", null, null, null, null, null, null, null, null, null, null, agentStep.runId(), agentStep, null, null, null, null, null);
    }

    public static ChatStreamEvent toolConfirmationRequired(UUID agentRunId, String toolName, String toolInput, String reason) {
        return new ChatStreamEvent("tool_confirmation_required", "waiting_confirmation", "需要确认联网检索", null, null, null, null, null, null, null, null, agentRunId, null, toolName, toolInput, reason, null, null);
    }

    public static ChatStreamEvent done() {
        return new ChatStreamEvent("done", "done", "回答完毕", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static ChatStreamEvent error(String message) {
        return new ChatStreamEvent("error", "error", message, null, null, false, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
