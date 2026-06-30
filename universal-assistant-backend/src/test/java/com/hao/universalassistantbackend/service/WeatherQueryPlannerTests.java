package com.hao.universalassistantbackend.service;

import com.hao.universalassistantbackend.model.ChatMessage;
import com.hao.universalassistantbackend.model.WeatherPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeatherQueryPlannerTests {

    private final WeatherQueryPlanner planner = new WeatherQueryPlanner();

    @Test
    void resolvesReferencedGuangzhouAttractionsIntoDistrictWeatherQueries() {
        List<ChatMessage> history = List.of(
                new ChatMessage("assistant", """
                        广州一日游路线：
                        1. 陈家祠
                        2. 永庆坊
                        3. 沙面
                        4. 石室圣心大教堂
                        5. 广州塔
                        6. 珠江夜游
                        """)
        );

        WeatherPlan plan = planner.plan("请帮我查一下明天这些景点的天气情况，或者帮我细化整个行程", history, "");

        assertFalse(plan.needsClarification());
        assertTrue(plan.queries().stream().anyMatch(query -> "广州荔湾".equals(query.resolvedLocation())));
        assertTrue(plan.queries().stream().anyMatch(query -> "广州越秀".equals(query.resolvedLocation())));
        assertTrue(plan.queries().stream().anyMatch(query -> "广州海珠".equals(query.resolvedLocation())));
        assertTrue(plan.queries().stream().allMatch(query -> "明天".equals(query.dateText())));
    }

    @Test
    void prefersSpecificDistrictOverCityLevelLocation() {
        WeatherPlan plan = planner.plan("广州黄埔明天天气怎么样？", List.of(), "");

        assertTrue(plan.queries().stream().anyMatch(query -> "广州黄埔".equals(query.resolvedLocation())));
        assertFalse(plan.queries().stream().anyMatch(query -> "广州".equals(query.resolvedLocation())));
    }
}
