package com.hao.universalassistantbackend.context;

import com.hao.universalassistantbackend.model.ChatMessage;
import com.hao.universalassistantbackend.model.MemoryHit;
import com.hao.universalassistantbackend.model.SearchResult;
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
}
