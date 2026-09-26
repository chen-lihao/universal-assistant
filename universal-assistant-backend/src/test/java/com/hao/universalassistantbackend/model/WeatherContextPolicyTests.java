package com.hao.universalassistantbackend.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WeatherContextPolicyTests {

    @Test
    void keepsWeatherAndTravelContextWithoutCareerMessages() {
        List<ChatMessage> history = List.of(
                new ChatMessage("user", "我的简历写了广州项目经历和天气系统。"),
                new ChatMessage("assistant", "广州番禺的景点行程已整理好。"),
                new ChatMessage("assistant", "广州明天的天气预报还没有核实。")
        );

        assertThat(WeatherContextPolicy.relatedHistory(history))
                .extracting(ChatMessage::content)
                .containsExactly("广州番禺的景点行程已整理好。", "广州明天的天气预报还没有核实。");
    }
}
