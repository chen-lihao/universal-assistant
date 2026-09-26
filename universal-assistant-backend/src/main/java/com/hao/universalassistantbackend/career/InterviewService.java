package com.hao.universalassistantbackend.career;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hao.universalassistantbackend.rag.KnowledgeHit;
import com.hao.universalassistantbackend.rag.KnowledgeService;
import com.hao.universalassistantbackend.skill.ActiveSkill;
import com.hao.universalassistantbackend.skill.SkillService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.hao.universalassistantbackend.career.CareerModels.*;

@Service
public class InterviewService {

    private static final String MODEL = "deepseek-v4-pro";
    private static final int DEFAULT_QUESTION_COUNT = 6;
    private static final int MIN_QUESTION_COUNT = 3;
    private static final int MAX_QUESTION_COUNT = 12;

    private final CareerRepository repository;
    private final KnowledgeService knowledgeService;
    private final SkillService skillService;
    private final CareerPrivacyService privacyService;
    private final ObjectProvider<ChatClient> chatClientProvider;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public InterviewService(CareerRepository repository,
                            KnowledgeService knowledgeService,
                            SkillService skillService,
                            CareerPrivacyService privacyService,
                            @Qualifier("deepSeekChatClient") ObjectProvider<ChatClient> chatClientProvider,
                            ObjectMapper objectMapper,
                            @Value("${spring.ai.dashscope.api-key:}") String apiKey) {
        this.repository = repository;
        this.knowledgeService = knowledgeService;
        this.skillService = skillService;
        this.privacyService = privacyService;
        this.chatClientProvider = chatClientProvider;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
    }

    public InterviewSession start(StartInterviewRequest request) {
        require(request != null, "面试请求不能为空。");
        CareerProfile profile = profile(request.profileId());
        ResumeVersion resume = resume(request.resumeVersionId());
        JobTarget target = target(request.jobTargetId());
        require(profile.id().equals(resume.profileId()) && profile.id().equals(target.profileId()),
                "简历和岗位不属于当前求职档案。");

        String mode = "formal".equalsIgnoreCase(request.mode()) ? "formal" : "practice";
        int maxQuestions = clampQuestionCount(request.maxQuestions());
        List<KnowledgeHit> evidence = knowledgeService.search(
                target.jobTitle() + " " + target.description(),
                profile.knowledgeBaseId()
        );
        List<InterviewQuestion> questions = generateQuestions(resume, target, evidence, maxQuestions);
        UUID sessionId = repository.createInterviewSession(
                profile.id(), resume.id(), target.id(), mode, questions.size(), questions
        );
        return get(sessionId);
    }

    public InterviewSession answer(UUID sessionId, AnswerInterviewRequest request) {
        require(request != null && StringUtils.hasText(request.answer()), "回答内容不能为空。");
        CareerRepository.InterviewSessionContext context = context(sessionId);
        require("active".equals(context.status()), "当前模拟面试已经结束。");
        require(context.currentIndex() < context.questions().size(), "当前没有待回答的问题。");

        ResumeVersion resume = resume(context.resumeVersionId());
        JobTarget target = target(context.jobTargetId());
        InterviewQuestion question = context.questions().get(context.currentIndex());
        InterviewFeedback feedback = evaluateAnswer(resume, target, question, request.answer());
        repository.saveInterviewTurn(
                sessionId,
                context.currentIndex(),
                question.question(),
                request.answer().trim(),
                feedback
        );

        int answered = context.currentIndex() + 1;
        if (answered >= context.maxQuestions() || answered >= context.questions().size()) {
            List<InterviewTurn> turns = repository.listInterviewTurns(sessionId, true);
            repository.completeInterview(sessionId, generateFinalReport(target, turns));
        }
        return get(sessionId);
    }

    public InterviewSession get(UUID sessionId) {
        CareerRepository.InterviewSessionContext context = context(sessionId);
        boolean completed = "completed".equals(context.status());
        boolean includeFeedback = completed || "practice".equals(context.mode());
        List<InterviewTurn> turns = repository.listInterviewTurns(sessionId, includeFeedback);
        InterviewFeedback latestFeedback = turns.isEmpty() ? null : turns.get(turns.size() - 1).feedback();
        InterviewQuestion currentQuestion = !completed && context.currentIndex() < context.questions().size()
                ? context.questions().get(context.currentIndex())
                : null;
        return new InterviewSession(
                context.id(),
                context.profileId(),
                context.resumeVersionId(),
                context.jobTargetId(),
                context.mode(),
                context.status(),
                context.currentIndex(),
                context.maxQuestions(),
                currentQuestion,
                latestFeedback,
                turns,
                context.finalReport(),
                context.startedAt(),
                context.completedAt()
        );
    }

    public List<InterviewSessionSummary> list(UUID profileId) {
        profile(profileId);
        return repository.listInterviewSessions(profileId);
    }

    private List<InterviewQuestion> generateQuestions(ResumeVersion resume,
                                                       JobTarget target,
                                                       List<KnowledgeHit> evidence,
                                                       int maxQuestions) {
        ChatClient client = configuredClient();
        if (client == null) {
            return fallbackQuestions(target, maxQuestions);
        }
        StringBuilder prompt = new StringBuilder();
        appendSkills(prompt, "模拟面试 简历深挖 岗位胜任力 STAR 追问");
        prompt.append("目标岗位：").append(target.jobTitle()).append('\n')
                .append("公司：").append(text(target.company(), "未指定")).append("\n\n")
                .append("岗位描述：\n").append(trim(target.description(), 6000)).append("\n\n")
                .append("候选人简历：\n")
                .append(trim(privacyService.redactForExternalProcessing(resume.content()), 9000)).append("\n\n")
                .append("检索证据：\n");
        evidence.stream().limit(8).forEach(hit -> prompt.append("- ")
                .append(hit.title()).append(": ").append(trim(hit.content(), 700)).append('\n'));
        prompt.append("\n请生成 ").append(maxQuestions).append(" 道题，覆盖自我介绍、项目深挖、岗位能力和行为面试。")
                .append("只返回 JSON 数组，元素结构为 {\"index\":1,\"category\":\"...\",\"question\":\"...\",\"focus\":\"...\"}。");
        try {
            String result = client.prompt()
                    .system("你是严谨的中文面试官。问题必须基于给定简历和岗位，不得虚构候选人经历。只返回合法 JSON。")
                    .options(DashScopeChatOptions.builder().model(MODEL).temperature(0.35).maxToken(2200).build())
                    .user(trim(prompt.toString(), 22_000))
                    .call()
                    .content();
            List<InterviewQuestion> parsed = readJsonArray(result);
            if (!parsed.isEmpty()) {
                List<InterviewQuestion> normalized = new ArrayList<>();
                for (int index = 0; index < Math.min(maxQuestions, parsed.size()); index++) {
                    InterviewQuestion question = parsed.get(index);
                    if (StringUtils.hasText(question.question())) {
                        normalized.add(new InterviewQuestion(
                                normalized.size(),
                                text(question.category(), "综合能力"),
                                question.question().trim(),
                                text(question.focus(), "表达清晰、事实具体、与岗位相关")
                        ));
                    }
                }
                if (normalized.size() >= MIN_QUESTION_COUNT) {
                    return normalized;
                }
            }
        } catch (RuntimeException ignored) {
            // Keep the interview workflow available when the model is temporarily unavailable.
        }
        return fallbackQuestions(target, maxQuestions);
    }

    private InterviewFeedback evaluateAnswer(ResumeVersion resume,
                                               JobTarget target,
                                               InterviewQuestion question,
                                               String answer) {
        ChatClient client = configuredClient();
        if (client == null) {
            return fallbackFeedback(answer);
        }
        StringBuilder prompt = new StringBuilder();
        appendSkills(prompt, "模拟面试 回答评估 STAR 反馈");
        prompt.append("岗位：").append(target.jobTitle()).append("\n")
                .append("问题：").append(question.question()).append("\n")
                .append("考察重点：").append(question.focus()).append("\n")
                .append("候选人回答：").append(trim(answer, 6000)).append("\n\n")
                .append("简历事实（仅用于核验，不可补造）：\n")
                .append(trim(privacyService.redactForExternalProcessing(resume.content()), 8000)).append("\n\n")
                .append("只返回 JSON：{\"score\":1,\"summary\":\"...\",\"strengths\":[\"...\"],")
                .append("\"improvements\":[\"...\"],\"exampleAnswer\":\"...\"}。score 为 1-5。")
                .append("示例回答只能重组已知事实；证据不足时使用占位提示，不得编造数据。");
        try {
            String result = client.prompt()
                    .system("你是证据优先的中文面试教练。评价具体、克制，不得虚构候选人经历。只返回合法 JSON。")
                    .options(DashScopeChatOptions.builder().model(MODEL).temperature(0.2).maxToken(1400).build())
                    .user(trim(prompt.toString(), 20_000))
                    .call()
                    .content();
            InterviewFeedback parsed = readJsonObject(result, InterviewFeedback.class);
            if (parsed != null) {
                return normalizeFeedback(parsed, resume.content() + "\n" + answer);
            }
        } catch (RuntimeException ignored) {
            // Return deterministic coaching instead of leaving the session in an active wait state.
        }
        return fallbackFeedback(answer);
    }

    private String generateFinalReport(JobTarget target, List<InterviewTurn> turns) {
        ChatClient client = configuredClient();
        if (client != null) {
            StringBuilder prompt = new StringBuilder("请基于以下模拟面试记录输出中文总结，包含总体评价、优势、三项优先改进和下一步训练计划。不要虚构。\n\n");
            prompt.append("岗位：").append(target.jobTitle()).append("\n\n");
            for (InterviewTurn turn : turns) {
                prompt.append("问题：").append(turn.question()).append("\n回答：")
                        .append(trim(privacyService.redactForExternalProcessing(turn.answer()), 1800)).append("\n评分：")
                        .append(turn.feedback() == null ? "未评分" : turn.feedback().score()).append("\n\n");
            }
            try {
                String result = client.prompt()
                        .system("你是中文面试教练。输出简洁 Markdown，不得添加记录中不存在的候选人事实。")
                        .options(DashScopeChatOptions.builder().model(MODEL).temperature(0.2).maxToken(1600).build())
                        .user(trim(prompt.toString(), 20_000))
                        .call()
                        .content();
                if (StringUtils.hasText(result)) {
                    return result.trim();
                }
            } catch (RuntimeException ignored) {
                // Fall through to the local report.
            }
        }
        double average = turns.stream()
                .filter(turn -> turn.feedback() != null)
                .mapToInt(turn -> turn.feedback().score())
                .average()
                .orElse(0);
        return "## 模拟面试总结\n\n"
                + "- 完成题数：" + turns.size() + "\n"
                + "- 平均评分：" + String.format("%.1f/5", average) + "\n"
                + "- 下一步：优先补充量化结果、个人贡献和复盘结论，并继续使用 STAR 结构练习。";
    }

    private List<InterviewQuestion> fallbackQuestions(JobTarget target, int maxQuestions) {
        List<InterviewQuestion> defaults = List.of(
                new InterviewQuestion(0, "自我介绍", "请用 2 分钟介绍你自己，并说明为什么适合" + target.jobTitle() + "岗位。", "岗位匹配和表达结构"),
                new InterviewQuestion(1, "项目深挖", "请选择一段与目标岗位最相关的项目经历，说明背景、你的职责、关键行动和结果。", "STAR 结构和个人贡献"),
                new InterviewQuestion(2, "问题解决", "讲一次你遇到复杂问题或失败的经历。你如何定位原因并推动解决？", "分析能力和复盘"),
                new InterviewQuestion(3, "协作沟通", "描述一次跨团队协作出现分歧的经历，你如何达成一致？", "沟通和影响力"),
                new InterviewQuestion(4, "岗位能力", "结合岗位要求，说明你最有把握的能力和仍需补足的能力。", "自我认知和岗位理解"),
                new InterviewQuestion(5, "求职动机", "你为什么选择这个岗位和公司？未来两年的职业目标是什么？", "动机真实性和稳定性"),
                new InterviewQuestion(6, "结果导向", "请举例说明你如何定义并衡量一项工作的成功。", "指标意识"),
                new InterviewQuestion(7, "反向提问", "如果让你向面试官提三个问题，你最想了解什么？", "判断力和准备度")
        );
        return defaults.subList(0, Math.min(maxQuestions, defaults.size()));
    }

    private InterviewFeedback fallbackFeedback(String answer) {
        List<String> improvements = new ArrayList<>();
        if (answer.length() < 80) {
            improvements.add("回答偏短，补充具体情境、个人行动和可验证结果。 ");
        }
        improvements.add("使用 STAR 结构，并明确区分团队成果与个人贡献。");
        improvements.add("涉及数字或结果时，只使用能够被简历或事实材料支持的信息。");
        return new InterviewFeedback(
                answer.length() >= 120 ? 3 : 2,
                "模型暂不可用，已完成基础结构检查。",
                StringUtils.hasText(answer) ? List.of("已正面回应问题") : List.of(),
                improvements,
                "可按“背景—目标—行动—结果—复盘”重新组织当前回答；缺少的数据请保留待补充标记。"
        );
    }

    private InterviewFeedback normalizeFeedback(InterviewFeedback feedback, String evidenceCorpus) {
        String exampleAnswer = CareerEvidencePolicy.claimsGrounded(feedback.exampleAnswer(), evidenceCorpus)
                ? feedback.exampleAnswer().trim()
                : "请按“背景—目标—个人行动—可验证结果—复盘”组织回答；缺少的技术细节或数据保留为待补充，不要在面试中编造。";
        return new InterviewFeedback(
                Math.max(1, Math.min(5, feedback.score())),
                text(feedback.summary(), "已完成回答评估。"),
                feedback.strengths() == null ? List.of() : feedback.strengths().stream().limit(5).toList(),
                feedback.improvements() == null ? List.of() : feedback.improvements().stream().limit(5).toList(),
                exampleAnswer
        );
    }

    private void appendSkills(StringBuilder prompt, String query) {
        for (ActiveSkill skill : skillService.selectRelevant(query)) {
            prompt.append("Skill ").append(skill.name()).append(":\n")
                    .append(trim(skill.instructions(), 4000)).append("\n\n");
        }
    }

    private List<InterviewQuestion> readJsonArray(String value) {
        String json = extractJson(value, '[', ']');
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private <T> T readJsonObject(String value, Class<T> type) {
        String json = extractJson(value, '{', '}');
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private String extractJson(String value, char open, char close) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        int start = normalized.indexOf(open);
        int end = normalized.lastIndexOf(close);
        return start >= 0 && end > start ? normalized.substring(start, end + 1) : null;
    }

    private CareerRepository.InterviewSessionContext context(UUID id) {
        require(id != null, "面试会话不能为空。");
        try {
            return repository.findInterviewContext(id);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("模拟面试不存在。", ex);
        }
    }

    private CareerProfile profile(UUID id) {
        require(id != null, "求职档案不能为空。");
        return repository.findProfile(id).orElseThrow(() -> new IllegalArgumentException("求职档案不存在。"));
    }

    private ResumeVersion resume(UUID id) {
        require(id != null, "简历版本不能为空。");
        return repository.findResume(id).orElseThrow(() -> new IllegalArgumentException("简历版本不存在。"));
    }

    private JobTarget target(UUID id) {
        require(id != null, "目标岗位不能为空。");
        return repository.findJobTarget(id).orElseThrow(() -> new IllegalArgumentException("目标岗位不存在。"));
    }

    private ChatClient configuredClient() {
        if (!StringUtils.hasText(apiKey) || "missing-api-key".equals(apiKey)) {
            return null;
        }
        return chatClientProvider.getIfAvailable();
    }

    private int clampQuestionCount(Integer requested) {
        int count = requested == null ? DEFAULT_QUESTION_COUNT : requested;
        return Math.max(MIN_QUESTION_COUNT, Math.min(MAX_QUESTION_COUNT, count));
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private String trim(String value, int max) {
        if (value == null || value.length() <= max) {
            return value == null ? "" : value;
        }
        return value.substring(0, max - 1) + "…";
    }

    private String text(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
