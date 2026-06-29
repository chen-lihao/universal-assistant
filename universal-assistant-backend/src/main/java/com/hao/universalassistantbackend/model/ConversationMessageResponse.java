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
        Instant createdAt
) {
}
