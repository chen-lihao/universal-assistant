package com.hao.universalassistantbackend.model;

import java.util.List;
import java.util.UUID;

public record ConversationMessagesResponse(
        UUID conversationId,
        List<ConversationMessageResponse> messages
) {
}
