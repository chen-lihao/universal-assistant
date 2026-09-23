package com.hao.universalassistantbackend.skill;

import java.util.List;

public record SkillDescriptor(String name, String description, List<String> allowedTools, String source) {
}
