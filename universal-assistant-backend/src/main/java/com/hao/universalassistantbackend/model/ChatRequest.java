package com.hao.universalassistantbackend.model;

import java.util.List;

public record ChatRequest(String message, Boolean realtimeSearch, String model, List<ChatMessage> history) {
}
