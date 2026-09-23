package com.hao.universalassistantbackend.context;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class TokenBudgetManager {

    private final int maxContextCharacters;

    public TokenBudgetManager(@Value("${assistant.context.max-characters:28000}") int maxContextCharacters) {
        this.maxContextCharacters = Math.max(8000, maxContextCharacters);
    }

    public String fit(String prompt, String userQuestion) {
        if (prompt == null || prompt.length() <= maxContextCharacters) {
            return prompt == null ? "" : prompt;
        }

        String suffix = StringUtils.hasText(userQuestion) ? "\n\nUser question:\n" + userQuestion : "";
        int available = Math.max(1000, maxContextCharacters - suffix.length());
        return prompt.substring(0, available) + "\n\n[上下文已按预算截断]" + suffix;
    }

    public String trimSection(String content, int maxCharacters) {
        if (!StringUtils.hasText(content) || content.length() <= maxCharacters) {
            return content == null ? "" : content;
        }
        return content.substring(0, Math.max(1, maxCharacters - 1)) + "…";
    }
}
