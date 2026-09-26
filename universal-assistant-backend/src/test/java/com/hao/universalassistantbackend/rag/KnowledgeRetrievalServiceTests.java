package com.hao.universalassistantbackend.rag;

import com.hao.universalassistantbackend.skill.ActiveSkill;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeRetrievalServiceTests {

    private final KnowledgeService knowledgeService = mock(KnowledgeService.class);
    private final KnowledgeRetrievalService retrievalService = new KnowledgeRetrievalService(knowledgeService);

    @Test
    void weatherNeverRetrievesResumeOrGeneralKnowledge() {
        ActiveSkill skill = new ActiveSkill("knowledge-qna", "知识库问答", "", List.of());

        var result = retrievalService.retrieve("现在广州番禺天气怎么样？", true, List.of(skill));

        assertThat(result.hits()).isEmpty();
        assertThat(result.searched()).isFalse();
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void careerQuestionRequiresProfileScopedTool() {
        var result = retrievalService.retrieve("根据我的简历文档分析岗位匹配", false, List.of());

        assertThat(result.searched()).isFalse();
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void explicitDocumentQuestionUsesOnlyDefaultKnowledgeBase() {
        UUID baseId = KnowledgeService.DEFAULT_KNOWLEDGE_BASE_ID;
        KnowledgeHit hit = new KnowledgeHit(UUID.randomUUID(), UUID.randomUUID(), "README", null, "启动项目", 0.8);
        when(knowledgeService.search("根据本地文档说明如何启动项目", baseId)).thenReturn(List.of(hit));

        var result = retrievalService.retrieve("根据本地文档说明如何启动项目", false, List.of());

        assertThat(result.hits()).containsExactly(hit);
        assertThat(result.knowledgeBaseId()).isEqualTo(baseId);
        verify(knowledgeService).search("根据本地文档说明如何启动项目", baseId);
    }

    @Test
    void ordinaryQuestionDoesNotSearchLocalDocuments() {
        var result = retrievalService.retrieve("解释一下 Java record", false, List.of());

        assertThat(result.searched()).isFalse();
        verifyNoInteractions(knowledgeService);
    }
}
