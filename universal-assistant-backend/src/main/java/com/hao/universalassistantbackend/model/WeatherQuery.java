package com.hao.universalassistantbackend.model;

public record WeatherQuery(
        String locationText,
        String resolvedLocation,
        String dateText,
        String targetDate,
        String source,
        double confidence
) {

    public String callText() {
        return "get_weather(location=%s, date=%s)".formatted(resolvedLocation, targetDate);
    }

    public String displayText() {
        return "%s / %s（%s）".formatted(resolvedLocation, dateText, targetDate);
    }
}
