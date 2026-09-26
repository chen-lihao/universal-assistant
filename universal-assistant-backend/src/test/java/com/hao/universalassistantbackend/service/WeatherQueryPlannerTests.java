package com.hao.universalassistantbackend.service;

import com.hao.universalassistantbackend.model.ChatMessage;
import com.hao.universalassistantbackend.model.WeatherPlan;
import com.hao.universalassistantbackend.model.WeatherContextPolicy;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void resolvesPendingWeatherFollowUpFromRecentContext() {
        List<ChatMessage> history = List.of(
                new ChatMessage("user", "广州今天适合去哪些景点？"),
                new ChatMessage("assistant", "目前只查到了今天的天气。如果你想让我帮你对比未来几天找出最佳出行日，我可以再查接下来几天的预报。需要吗？")
        );

        WeatherPlan plan = planner.plan("""
                用户确认继续上一轮 assistant 提出的后续天气任务：查询未来3天天气预报，并对比找出最佳出行日。
                请结合最近会话上下文中的城市、景点或行程地点。
                """, history, "");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));

        assertFalse(plan.needsClarification());
        assertEquals(3, plan.queries().size());
        assertTrue(plan.queries().stream().allMatch(query -> "广州".equals(query.resolvedLocation())));
        assertTrue(plan.queries().stream().anyMatch(query -> today.plusDays(2).toString().equals(query.targetDate())));
    }

    @Test
    void asksForLocationInsteadOfTreatingResumeOrPronounAsWeatherLocation() {
        List<ChatMessage> history = WeatherContextPolicy.relatedHistory(List.of(
                new ChatMessage("user", "简历里写了广州项目经历。")
        ));

        WeatherPlan plan = planner.plan("这里天气怎么样？", history, "");

        assertTrue(plan.needsClarification());
        assertTrue(plan.queries().isEmpty());
    }
}
