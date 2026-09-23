package com.hao.universalassistantbackend.tool;

import java.util.List;

public record ToolDescriptor(
        String name,
        String description,
        List<String> permissions,
        boolean confirmationRequired,
        int timeoutSeconds,
        int maxRetries
) {
}
