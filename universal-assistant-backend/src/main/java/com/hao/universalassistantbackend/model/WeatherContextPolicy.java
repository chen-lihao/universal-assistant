package com.hao.universalassistantbackend.model;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

public final class WeatherContextPolicy {

    private WeatherContextPolicy() {
    }

    public static List<ChatMessage> relatedHistory(List<ChatMessage> history) {
        if (history == null) {
            return List.of();
        }
        return history.stream()
                .filter(item -> item != null && StringUtils.hasText(item.content()))
                .filter(item -> {
                    String text = item.content().toLowerCase(Locale.ROOT);
                    return (text.contains("天气") || text.contains("气温") || text.contains("预报")
                            || text.contains("降雨") || text.contains("行程") || text.contains("景点")
                            || text.contains("weather") || text.contains("forecast"))
                            && !(text.contains("简历") || text.contains("求职") || text.contains("岗位")
                            || text.contains("面试") || text.contains("工作经历") || text.contains("resume"));
                })
                .toList();
    }
}
