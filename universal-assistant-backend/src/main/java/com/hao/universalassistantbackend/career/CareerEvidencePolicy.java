package com.hao.universalassistantbackend.career;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CareerEvidencePolicy {

    private static final Pattern LATIN_OR_NUMBER_TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9+.#_-]*|\\d+(?:\\.\\d+)?%?");
    private static final List<String> PROTECTED_CLAIM_TERMS = List.of(
            "主导", "负责", "独立", "带领", "管理", "设计", "架构", "开发", "实现", "落地", "优化", "重构",
            "排查", "解决", "提升", "降低", "增长", "节省", "熟练", "精通", "微服务", "分布式", "缓存",
            "消息队列", "高并发", "高可用", "数据库", "算法", "部署", "运维", "测试", "审查", "复盘"
    );

    private CareerEvidencePolicy() {
    }

    static boolean claimsGrounded(String generatedText, String evidenceCorpus) {
        return claimIssues(generatedText, evidenceCorpus).isEmpty();
    }

    static List<String> claimIssues(String generatedText, String evidenceCorpus) {
        if (!StringUtils.hasText(generatedText) || !StringUtils.hasText(evidenceCorpus)) {
            return List.of("建议文本或原始简历为空。");
        }
        List<String> issues = new ArrayList<>();
        Set<String> evidenceTokens = tokens(evidenceCorpus);
        Matcher matcher = LATIN_OR_NUMBER_TOKEN.matcher(generatedText);
        while (matcher.find()) {
            if (!evidenceTokens.contains(matcher.group().toLowerCase(Locale.ROOT))) {
                issues.add("建议包含简历中未出现的术语或数字：" + matcher.group());
                if (issues.size() >= 3) return issues;
            }
        }
        for (String term : PROTECTED_CLAIM_TERMS) {
            if (generatedText.contains(term) && !evidenceCorpus.contains(term)) {
                issues.add("建议新增了简历未证实的能力或职责：" + term);
                if (issues.size() >= 3) return issues;
            }
        }
        return issues;
    }

    static String locateOriginal(String excerpt, String corpus) {
        if (!StringUtils.hasText(excerpt) || !StringUtils.hasText(corpus)) return null;
        String needle = compact(excerpt);
        StringBuilder haystack = new StringBuilder();
        List<Integer> positions = new ArrayList<>();
        for (int index = 0; index < corpus.length(); index++) {
            if (!Character.isWhitespace(corpus.charAt(index))) {
                haystack.append(corpus.charAt(index));
                positions.add(index);
            }
        }
        int start = haystack.indexOf(needle);
        return start < 0 ? null : corpus.substring(positions.get(start), positions.get(start + needle.length() - 1) + 1);
    }

    private static String compact(String content) {
        StringBuilder result = new StringBuilder(content.length());
        for (int index = 0; index < content.length(); index++) {
            if (!Character.isWhitespace(content.charAt(index))) result.append(content.charAt(index));
        }
        return result.toString();
    }

    private static Set<String> tokens(String content) {
        Set<String> tokens = new HashSet<>();
        Matcher matcher = LATIN_OR_NUMBER_TOKEN.matcher(content);
        while (matcher.find()) {
            tokens.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        return tokens;
    }
}
