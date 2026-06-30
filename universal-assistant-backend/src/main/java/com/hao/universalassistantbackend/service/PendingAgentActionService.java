package com.hao.universalassistantbackend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hao.universalassistantbackend.entity.ConversationEntity;
import com.hao.universalassistantbackend.entity.MessageEntity;
import com.hao.universalassistantbackend.entity.PendingAgentActionEntity;
import com.hao.universalassistantbackend.repository.PendingAgentActionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class PendingAgentActionService {

    public static final String WEATHER_FORECAST_COMPARE = "weather_forecast_compare";

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);
    private static final int ANSWER_SNIPPET_LIMIT = 900;

    private final PendingAgentActionRepository pendingAgentActionRepository;
    private final ObjectMapper objectMapper;

    public PendingAgentActionService(PendingAgentActionRepository pendingAgentActionRepository,
                                     ObjectMapper objectMapper) {
        this.pendingAgentActionRepository = pendingAgentActionRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PendingActionResolution resolve(ConversationEntity conversation, MessageEntity userMessage, String userInput) {
        Optional<PendingAgentActionEntity> activeAction = latestWaitingAction(conversation);
        if (activeAction.isEmpty()) {
            return PendingActionResolution.none();
        }

        PendingAgentActionEntity action = activeAction.get();
        if (isExpired(action)) {
            finish(action, "expired", userMessage);
            return PendingActionResolution.none();
        }

        if (isNegativeReply(userInput)) {
            finish(action, "rejected", userMessage);
            return PendingActionResolution.rejected("好的，已取消继续执行上一轮建议。");
        }

        if (isAffirmativeReply(userInput)) {
            finish(action, "consumed", userMessage);
            return PendingActionResolution.confirmed(action.getEffectiveMessage());
        }

        finish(action, "ignored", userMessage);
        return PendingActionResolution.none();
    }

    @Transactional
    public void registerFromAssistantTurn(ConversationEntity conversation,
                                          MessageEntity assistantMessage,
                                          String userMessage,
                                          String assistantAnswer) {
        expireWaitingActions(conversation);
        if (!shouldRegisterWeatherForecastCompare(assistantAnswer)) {
            return;
        }

        PendingAgentActionEntity action = new PendingAgentActionEntity();
        action.setConversation(conversation);
        action.setSourceMessageId(assistantMessage.getId());
        action.setActionType(WEATHER_FORECAST_COMPARE);
        action.setStatus("waiting");
        action.setQuestion(extractQuestion(assistantAnswer));
        action.setEffectiveMessage(buildWeatherCompareEffectiveMessage(assistantAnswer));
        action.setParamsJson(toJson(Map.of(
                "source", "assistant_follow_up",
                "type", WEATHER_FORECAST_COMPARE,
                "userMessage", safeText(userMessage),
                "assistantQuestion", safeText(extractQuestion(assistantAnswer))
        )));
        action.setExpiresAt(Instant.now().plus(DEFAULT_TTL));
        pendingAgentActionRepository.save(action);
    }

    @Transactional(readOnly = true)
    public Optional<PendingAgentActionEntity> latestWaitingAction(ConversationEntity conversation) {
        if (conversation == null || conversation.getId() == null) {
            return Optional.empty();
        }

        return pendingAgentActionRepository
                .findByConversation_IdAndStatusOrderByCreatedAtDesc(conversation.getId(), "waiting")
                .stream()
                .findFirst();
    }

    private void expireWaitingActions(ConversationEntity conversation) {
        if (conversation == null || conversation.getId() == null) {
            return;
        }

        for (PendingAgentActionEntity action : pendingAgentActionRepository.findByConversation_IdAndStatusOrderByCreatedAtDesc(conversation.getId(), "waiting")) {
            finish(action, "superseded", null);
        }
    }

    private void finish(PendingAgentActionEntity action, String status, MessageEntity userMessage) {
        action.setStatus(status);
        action.setConsumedAt(Instant.now());
        if (userMessage != null) {
            action.setConsumedByMessageId(userMessage.getId());
        }
        pendingAgentActionRepository.save(action);
    }

    private boolean isExpired(PendingAgentActionEntity action) {
        return action.getExpiresAt() != null && action.getExpiresAt().isBefore(Instant.now());
    }

    private boolean shouldRegisterWeatherForecastCompare(String assistantAnswer) {
        if (!StringUtils.hasText(assistantAnswer)) {
            return false;
        }

        String normalized = assistantAnswer.toLowerCase(Locale.ROOT);
        boolean weather = normalized.contains("天气") || normalized.contains("预报") || normalized.contains("forecast");
        boolean future = normalized.contains("未来几天") || normalized.contains("接下来几天") || normalized.contains("未来") || normalized.contains("接下来");
        boolean compare = normalized.contains("最佳出行日") || normalized.contains("适合出行") || normalized.contains("对比");
        boolean asksConfirmation = normalized.contains("需要吗") || normalized.contains("要继续") || normalized.contains("是否需要") || normalized.contains("需要我");
        return weather && future && compare && asksConfirmation;
    }

    private boolean isAffirmativeReply(String input) {
        String normalized = compact(input);
        if (!StringUtils.hasText(normalized) || normalized.length() > 12) {
            return false;
        }

        return List.of(
                "需要",
                "要",
                "可以",
                "好",
                "好的",
                "行",
                "继续",
                "查一下",
                "帮我查",
                "是",
                "是的",
                "嗯",
                "ok",
                "yes",
                "y"
        ).contains(normalized);
    }

    private boolean isNegativeReply(String input) {
        String normalized = compact(input);
        if (!StringUtils.hasText(normalized) || normalized.length() > 16) {
            return false;
        }

        return normalized.contains("不需要")
                || normalized.contains("不用")
                || normalized.contains("先不用")
                || normalized.equals("否")
                || normalized.equals("不要")
                || normalized.equals("no")
                || normalized.equals("n");
    }

    private String compact(String input) {
        if (!StringUtils.hasText(input)) {
            return "";
        }

        return input.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "")
                .replaceAll("[，。！？?、,.!]", "")
                .trim();
    }

    private String buildWeatherCompareEffectiveMessage(String assistantAnswer) {
        return """
                用户确认继续上一轮 assistant 提出的后续天气任务：查询未来3天天气预报，并对比找出最佳出行日。
                请结合最近会话上下文中的城市、景点或行程地点；如果上下文有多个地点，请分别查询。
                优先使用天气工具完成查询；只有天气工具不可用时，再请求用户确认是否降级为实时网页检索。

                上一轮 assistant 的确认问题：
                %s
                """.formatted(trimForPrompt(extractQuestion(assistantAnswer), ANSWER_SNIPPET_LIMIT)).trim();
    }

    private String extractQuestion(String assistantAnswer) {
        if (!StringUtils.hasText(assistantAnswer)) {
            return "";
        }

        String trimmed = assistantAnswer.trim();
        int start = Math.max(trimmed.lastIndexOf("目前"), Math.max(trimmed.lastIndexOf("如果"), trimmed.lastIndexOf("需要")));
        if (start >= 0 && start < trimmed.length()) {
            return trimForPrompt(trimmed.substring(start), ANSWER_SNIPPET_LIMIT);
        }
        return trimForPrompt(trimmed, ANSWER_SNIPPET_LIMIT);
    }

    private String trimForPrompt(String value, int maxLength) {
        if (!StringUtils.hasText(value) || value.length() <= maxLength) {
            return value == null ? "" : value.trim();
        }
        return value.substring(0, maxLength).trim() + " ...";
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private String toJson(Map<String, String> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    public record PendingActionResolution(
            boolean matched,
            boolean confirmed,
            boolean rejected,
            String effectiveMessage,
            String directAnswer
    ) {

        public static PendingActionResolution none() {
            return new PendingActionResolution(false, false, false, "", "");
        }

        public static PendingActionResolution confirmed(String effectiveMessage) {
            return new PendingActionResolution(true, true, false, effectiveMessage, "");
        }

        public static PendingActionResolution rejected(String directAnswer) {
            return new PendingActionResolution(true, false, true, "", directAnswer);
        }
    }
}
