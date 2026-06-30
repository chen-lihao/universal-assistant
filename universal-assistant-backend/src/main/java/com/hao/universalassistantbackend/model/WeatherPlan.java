package com.hao.universalassistantbackend.model;

import java.util.List;

public record WeatherPlan(
        boolean weatherIntent,
        List<WeatherQuery> queries,
        List<String> referencedLocations,
        String note,
        boolean needsClarification,
        String clarificationQuestion,
        boolean truncated
) {

    public static WeatherPlan empty() {
        return new WeatherPlan(false, List.of(), List.of(), "", false, "", false);
    }

    public String stepSummary() {
        if (!weatherIntent) {
            return "未识别到天气查询意图。";
        }
        if (needsClarification) {
            return clarificationQuestion;
        }

        StringBuilder summary = new StringBuilder("天气查询规划：");
        if (!referencedLocations.isEmpty()) {
            summary.append("\n提取地点：").append(String.join("、", referencedLocations));
        }
        if (!queries.isEmpty()) {
            summary.append("\n拆分查询：");
            for (WeatherQuery query : queries) {
                summary.append("\n- ").append(query.displayText());
            }
        }
        if (truncated) {
            summary.append("\n查询数量较多，已按上限截断。");
        }
        if (note != null && !note.isBlank()) {
            summary.append("\n说明：").append(note);
        }
        return summary.toString();
    }
}
