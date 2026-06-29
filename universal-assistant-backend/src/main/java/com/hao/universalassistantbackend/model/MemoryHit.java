package com.hao.universalassistantbackend.model;

import java.util.UUID;

public record MemoryHit(
        UUID id,
        String content,
        double score
) {
}
