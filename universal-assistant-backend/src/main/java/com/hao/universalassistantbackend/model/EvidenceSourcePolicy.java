package com.hao.universalassistantbackend.model;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

public final class EvidenceSourcePolicy {

    private EvidenceSourcePolicy() {
    }

    public static List<SearchResult> weatherSources(List<SearchResult> sources) {
        if (sources == null) {
            return List.of();
        }
        return sources.stream().filter(EvidenceSourcePolicy::isWeatherSource).toList();
    }

    public static boolean isWeatherSource(SearchResult source) {
        if (source == null || !StringUtils.hasText(source.provider())) {
            return false;
        }
        if ("open-meteo".equals(source.provider())) {
            return true;
        }
        if (!"web".equals(source.provider())) {
            return false;
        }
        String text = ((source.title() == null ? "" : source.title()) + " "
                + (source.snippet() == null ? "" : source.snippet())).toLowerCase(Locale.ROOT);
        return text.contains("天气") || text.contains("气温") || text.contains("降雨")
                || text.contains("预报") || text.contains("weather") || text.contains("forecast")
                || text.contains("temperature");
    }
}
