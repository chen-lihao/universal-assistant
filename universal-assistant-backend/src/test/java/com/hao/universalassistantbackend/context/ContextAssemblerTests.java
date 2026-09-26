package com.hao.universalassistantbackend.context;

import com.hao.universalassistantbackend.model.ChatMessage;
import com.hao.universalassistantbackend.model.MemoryHit;
import com.hao.universalassistantbackend.model.SearchResult;
import com.hao.universalassistantbackend.model.WeatherReport;
import com.hao.universalassistantbackend.skill.ActiveSkill;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContextAssemblerTests {

    @Test
    void assemblesOrderedContextWithSkillsAndEvidence() {
        ContextAssembler assembler = new ContextAssembler(new TokenBudgetManager(12000));
        ContextRequest request = new ContextRequest(
                "项目如何启动？",
                "用户正在阅读项目文档。",
                List.of(new ChatMessage("user", "请查看项目说明")),
                List.of(new SearchResult("README", "http://localhost/doc", "使用 docker compose 启动数据库", "knowledge")),
                null,
                List.of(new MemoryHit(UUID.randomUUID(), "用户偏好中文回答", 0.9)),
                List.of(new ActiveSkill("knowledge-qna", "知识库问答", "严格依据文档回答", List.of("knowledge_search"))),
                "1. 检索文档\n2. 汇总答案",
                "deepseek-v4-pro",
                "DeepSeek V4 Pro",
                "DashScope",
                "Spring AI Alibaba"
        );

        String prompt = assembler.assemble(request);

        assertThat(prompt)
                .contains("Runtime model metadata")
                .contains("Activated skills")
                .contains("严格依据文档回答")
                .contains("Retrieved evidence")
                .contains("docker compose")
                .endsWith("项目如何启动？");
    }

    @Test
    void weatherContextExcludesCareerHistoryMemoryAndKnowledgeEvidence() {
        ContextAssembler assembler = new ContextAssembler(new TokenBudgetManager(12000));
        ContextRequest request = new ContextRequest(
                "现在广州天气怎么样？",
                "用户有一份 Java 后端简历。",
                List.of(
                        new ChatMessage("user", "请修改我的 Java 简历"),
                        new ChatMessage("assistant", "昨天广州天气晴朗。")
                ),
                List.of(
                        new SearchResult("Java 简历", "http://localhost/resume", "Spring Boot 项目经历", "knowledge"),
                        new SearchResult("广州天气", "https://example.com/weather", "广州今日天气参考", "web")
                ),
                WeatherReport.unavailable("广州", "天气服务不可用。", List.of()),
                List.of(new MemoryHit(UUID.randomUUID(), "用户负责后端开发。", 0.9)),
                List.of(),
                "",
                "deepseek-v4-pro", "DeepSeek V4 Pro", "DashScope", "Spring AI Alibaba"
        );

        String prompt = assembler.assemble(request);

        assertThat(prompt).contains("昨天广州天气晴朗。", "广州今日天气参考", "天气服务不可用。")
                .doesNotContain("Java 后端简历", "请修改我的 Java 简历", "Spring Boot 项目经历", "用户负责后端开发。");
    }
}
