package com.hao.universalassistantbackend.skill;

import java.util.List;

public record ActiveSkill(String name, String description, String instructions, List<String> allowedTools) {
}
