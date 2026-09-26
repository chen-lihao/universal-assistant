package com.hao.universalassistantbackend.skill;

import com.alibaba.cloud.ai.graph.skills.registry.classpath.ClasspathSkillRegistry;
import com.hao.universalassistantbackend.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SkillServiceTests {

    @Test
    void loadsAndSelectsProgressiveSkills() {
        ClasspathSkillRegistry registry = ClasspathSkillRegistry.builder()
                .classpathPath("skills")
                .autoLoad(true)
                .build();
        try {
            SkillService service = new SkillService(registry, new ToolRegistry(), true, 2);

            assertThat(service.listSkills()).hasSize(8);
            assertThat(service.selectRelevant("请查询广州明天的天气并调整行程"))
                    .extracting(ActiveSkill::name)
                    .contains("weather-planning");
            assertThat(service.selectRelevant("请查询广州明天的天气并调整行程").get(0).allowedTools())
                    .contains("get_weather");
            assertThat(service.selectRelevant("请根据 Java 后端岗位修改我的简历"))
                    .extracting(ActiveSkill::name)
                    .containsAnyOf("resume-review", "resume-tailoring");
            assertThat(service.selectRelevant("开始一场模拟面试"))
                    .extracting(ActiveSkill::name)
                    .contains("mock-interview");
        } finally {
            registry.close();
        }
    }
}
