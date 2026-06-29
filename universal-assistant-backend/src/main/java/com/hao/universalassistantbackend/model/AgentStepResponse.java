package com.hao.universalassistantbackend.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentStepResponse(
        UUID id,
        UUID runId,
        int index,
        String type,
        String title,
        String content,
        String status,
        Instant createdAt
) {
}
