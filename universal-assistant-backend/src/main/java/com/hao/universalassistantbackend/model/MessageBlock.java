package com.hao.universalassistantbackend.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageBlock(
        String id,
        String type,
        String content,
        Boolean streaming,
        UUID runId,
        List<AgentStepResponse> steps,
        Boolean collapsed,
        List<SearchResult> items,
        String name,
        Object input,
        String status,
        Object metadata,
        String phase,
        String text,
        String message,
        Boolean recoverable
) {

    public static MessageBlock markdown(String id, String content, boolean streaming) {
        return new MessageBlock(id, "markdown", content, streaming, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static MessageBlock execution(String id, UUID runId, List<AgentStepResponse> steps, boolean collapsed) {
        return new MessageBlock(id, "execution", null, null, runId, steps == null ? List.of() : steps, collapsed, null, null, null, null, null, null, null, null, null);
    }

    public static MessageBlock sources(String id, List<SearchResult> items) {
        return new MessageBlock(id, "sources", null, null, null, null, null, items == null ? List.of() : items, null, null, null, null, null, null, null, null);
    }

    public static MessageBlock status(String id, String phase, String text) {
        return new MessageBlock(id, "status", null, null, null, null, null, null, null, null, null, null, phase, text, null, null);
    }

    public static MessageBlock error(String id, String message, boolean recoverable) {
        return new MessageBlock(id, "error", null, null, null, null, null, null, null, null, null, null, "error", null, message, recoverable);
    }
}
