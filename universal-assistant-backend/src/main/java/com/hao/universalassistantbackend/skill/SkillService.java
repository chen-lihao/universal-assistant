package com.hao.universalassistantbackend.skill;

import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.hao.universalassistantbackend.tool.ToolRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class SkillService {

    private final SkillRegistry skillRegistry;
    private final ToolRegistry toolRegistry;
    private final boolean enabled;
    private final int maxActiveSkills;

    public SkillService(SkillRegistry skillRegistry,
                        ToolRegistry toolRegistry,
                        @Value("${assistant.skills.enabled:true}") boolean enabled,
                        @Value("${assistant.skills.max-active:2}") int maxActiveSkills) {
        this.skillRegistry = skillRegistry;
        this.toolRegistry = toolRegistry;
        this.enabled = enabled;
        this.maxActiveSkills = Math.max(1, maxActiveSkills);
    }

    public List<SkillDescriptor> listSkills() {
        return skillRegistry.listAll().stream()
                .map(this::toDescriptor)
                .toList();
    }

    public List<ActiveSkill> selectRelevant(String message) {
        if (!enabled || !StringUtils.hasText(message)) {
            return List.of();
        }

        return skillRegistry.listAll().stream()
                .map(metadata -> new ScoredSkill(metadata, score(metadata, message)))
                .filter(candidate -> candidate.score() > 0)
                .sorted(Comparator.comparingInt(ScoredSkill::score).reversed())
                .limit(maxActiveSkills)
                .map(candidate -> activate(candidate.metadata()))
                .filter(skill -> skill != null)
                .toList();
    }

    private ActiveSkill activate(SkillMetadata metadata) {
        try {
            List<String> allowedTools = metadata.getAllowedTools() == null
                    ? List.of()
                    : metadata.getAllowedTools().stream().filter(toolRegistry::contains).toList();
            return new ActiveSkill(metadata.getName(), metadata.getDescription(), metadata.loadFullContent(), allowedTools);
        } catch (IOException ex) {
            return null;
        }
    }

    private SkillDescriptor toDescriptor(SkillMetadata metadata) {
        List<String> allowedTools = metadata.getAllowedTools() == null ? List.of() : List.copyOf(metadata.getAllowedTools());
        return new SkillDescriptor(metadata.getName(), metadata.getDescription(), allowedTools, metadata.getSource());
    }

    private int score(SkillMetadata metadata, String message) {
        String haystack = message.toLowerCase(Locale.ROOT);
        Set<String> terms = terms(metadata.getName() + " " + metadata.getDescription());
        int score = 0;
        for (String term : terms) {
            if (term.length() >= 2 && haystack.contains(term)) {
                score += Math.min(8, term.length());
            }
        }
        return score + intentScore(metadata.getName(), haystack);
    }

    private int intentScore(String skillName, String message) {
        return switch (skillName) {
            case "resume-review" -> containsAny(message, "简历", "履历") ? 24 : 0;
            case "resume-tailoring" -> containsAny(message, "简历", "履历")
                    ? (containsAny(message, "修改", "优化", "定制", "改写", "润色") ? 32 : 20)
                    : 0;
            case "mock-interview" -> containsAny(message, "面试", "面经", "回答练习") ? 40 : 0;
            case "company-research" -> containsAny(message, "公司调研", "企业调研", "行业调研", "公司背景") ? 36 : 0;
            default -> 0;
        };
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> terms(String value) {
        Set<String> terms = new HashSet<>();
        if (!StringUtils.hasText(value)) {
            return terms;
        }
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsHan}a-z0-9_-]+", " ");
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2) {
                terms.add(token);
            }
        }
        List<String> chineseKeywords = List.of(
                "天气", "预报", "行程", "检索", "搜索", "实时", "网页", "文件", "转换", "编辑", "知识库", "文档", "资料", "问答",
                "简历", "求职", "岗位", "职位", "面试", "项目经历", "工作经历", "公司研究"
        );
        for (String keyword : chineseKeywords) {
            if (normalized.contains(keyword)) {
                terms.add(keyword);
            }
        }
        return terms;
    }

    private record ScoredSkill(SkillMetadata metadata, int score) {
    }
}
