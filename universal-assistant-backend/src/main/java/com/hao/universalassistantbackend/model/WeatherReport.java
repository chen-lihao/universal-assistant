package com.hao.universalassistantbackend.model;

import java.util.List;

public record WeatherReport(
        boolean available,
        String location,
        String summary,
        String context,
        List<SearchResult> sources
) {

    public static WeatherReport unavailable(String location, String message, List<SearchResult> sources) {
        return new WeatherReport(false, location, message, message, sources);
    }
}
