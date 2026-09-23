package com.hao.universalassistantbackend.skill;

import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.skills.registry.classpath.ClasspathSkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SkillConfiguration {

    @Bean(destroyMethod = "close")
    SkillRegistry assistantSkillRegistry() {
        return ClasspathSkillRegistry.builder()
                .classpathPath("skills")
                .autoLoad(true)
                .build();
    }

    @Bean
    SkillsAgentHook skillsAgentHook(SkillRegistry assistantSkillRegistry) {
        return SkillsAgentHook.builder()
                .skillRegistry(assistantSkillRegistry)
                .autoReload(false)
                .build();
    }
}
