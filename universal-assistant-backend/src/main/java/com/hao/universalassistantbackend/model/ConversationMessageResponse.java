package com.hao.universalassistantbackend.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ConversationMessageResponse(
        UUID id,
        String role,
        String content,
        String model,
        Boolean realtimeSearchUsed,
        Boolean modelAvailable,
        List<SearchResult> sources,
        String status,
        int revision,
        Instant editedAt,
        UUID agentRunId,
        List<AgentStepResponse> agentSteps,
        String pendingToolName,
        String pendingToolInput,
        String pendingToolReason,
        Instant createdAt
) {
}
