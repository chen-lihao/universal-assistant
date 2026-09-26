package com.hao.universalassistantbackend.rag;

import com.hao.universalassistantbackend.skill.ActiveSkill;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class KnowledgeRetrievalService {

    private final KnowledgeService knowledgeService;

    public KnowledgeRetrievalService(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    public RetrievalResult retrieve(String message, boolean weatherIntent, List<ActiveSkill> activeSkills) {
        if (weatherIntent) {
            return RetrievalResult.skipped("天气任务只使用天气工具和天气相关实时来源。");
        }
        if (!StringUtils.hasText(message)) {
            return RetrievalResult.skipped("问题为空。");
        }

        String normalized = message.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "简历", "履历", "求职", "岗位", "面试", "工作经历", "项目经历", "resume", "interview")) {
            return RetrievalResult.skipped("求职资料需要通过指定求职档案的工具检索。");
        }

        boolean knowledgeSkillActive = activeSkills != null && activeSkills.stream()
                .anyMatch(skill -> "knowledge-qna".equals(skill.name()));
        if (!knowledgeSkillActive && !containsAny(normalized,
                "知识库", "本地文档", "文档", "资料", "笔记", "说明书", "根据文件", "基于文件", "上传的文件", "导入的文件", "readme")) {
            return RetrievalResult.skipped("当前问题未请求本地知识库证据。");
        }

        UUID knowledgeBaseId = KnowledgeService.DEFAULT_KNOWLEDGE_BASE_ID;
        return new RetrievalResult(
                knowledgeService.search(message, knowledgeBaseId),
                knowledgeBaseId,
                "仅检索默认本地知识库。"
        );
    }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    public record RetrievalResult(List<KnowledgeHit> hits, UUID knowledgeBaseId, String reason) {
        public RetrievalResult {
            hits = List.copyOf(hits);
        }

        public static RetrievalResult skipped(String reason) {
            return new RetrievalResult(List.of(), null, reason);
        }

        public boolean searched() {
            return knowledgeBaseId != null;
        }
    }
}
