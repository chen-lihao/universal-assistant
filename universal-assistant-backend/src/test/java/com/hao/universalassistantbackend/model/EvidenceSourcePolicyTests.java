package com.hao.universalassistantbackend.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceSourcePolicyTests {

    @Test
    void weatherSourcesExcludeResumesAndUnrelatedWebPages() {
        SearchResult resume = new SearchResult("Java 后端简历", "http://localhost/resume", "Spring Boot 工作经历", "knowledge");
        SearchResult unrelated = new SearchResult("广州招聘", "https://example.com/jobs", "后端工程师岗位", "web");
        SearchResult forecast = new SearchResult("广州天气预报", "https://example.com/weather", "番禺今日气温", "web");
        SearchResult direct = new SearchResult("Open-Meteo", "https://open-meteo.com", "Current temperature", "open-meteo");

        assertThat(EvidenceSourcePolicy.weatherSources(List.of(resume, unrelated, forecast, direct)))
                .containsExactly(forecast, direct);
    }
}
