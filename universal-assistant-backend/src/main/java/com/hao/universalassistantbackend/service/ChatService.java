package com.hao.universalassistantbackend.service;

import com.hao.universalassistantbackend.entity.ConversationEntity;
import com.hao.universalassistantbackend.entity.MessageEntity;
import com.hao.universalassistantbackend.model.AgentMode;
import com.hao.universalassistantbackend.model.AgentRunContext;
import com.hao.universalassistantbackend.model.AgentStepResponse;
import com.hao.universalassistantbackend.model.CancelAgentRunRequest;
import com.hao.universalassistantbackend.model.ChatMessage;
import com.hao.universalassistantbackend.model.ChatRequest;
import com.hao.universalassistantbackend.model.ChatResponse;
import com.hao.universalassistantbackend.model.ChatStreamEvent;
import com.hao.universalassistantbackend.model.MemoryHit;
import com.hao.universalassistantbackend.model.MessageBlock;
import com.hao.universalassistantbackend.model.SearchResult;
import com.hao.universalassistantbackend.model.WeatherPlan;
import com.hao.universalassistantbackend.model.WeatherQuery;
import com.hao.universalassistantbackend.model.WeatherReport;
import com.hao.universalassistantbackend.tools.WeatherTools;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChatService {

    private static final String DEFAULT_MODEL = "deepseek-v4-pro";
    private static final Map<String, String> SUPPORTED_MODELS = Map.of(
            "deepseek-v4-pro", "DeepSeek V4 Pro",
            "deepseek-v4-flash", "DeepSeek V4 Flash"
    );
    private static final String MODEL_PROVIDER = "Alibaba Cloud DashScope";
    private static final String MODEL_INTEGRATION = "Spring Boot + Spring AI Alibaba + DashScope";
    private static final int SUMMARY_MIN_MESSAGES = 12;
    private static final int SUMMARY_REFRESH_INTERVAL = 6;
    private static final int SUMMARY_SOURCE_LIMIT = 20;
    private static final int PARTIAL_SAVE_CHAR_INTERVAL = 120;
    private static final long PARTIAL_SAVE_MILLIS = 500L;
    private static final String CANCELLED_ANSWER = "回答已中断。你可以编辑上一条问题后重新发送。";
    private static final Pattern LEADING_MODEL_COMMAND = Pattern.compile(
            "^\\s*(?:/model\\s+|@)([a-zA-Z0-9_.-]+)(?:\\s+|$)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern CHINESE_MONTH_DAY_PATTERN = Pattern.compile("\\d{1,2}\\s*月\\s*\\d{1,2}\\s*(?:日|号)?");
    private static final Pattern SHORT_MONTH_DAY_PATTERN = Pattern.compile("(?<!\\d)\\d{1,2}[/-]\\d{1,2}(?!\\d)");
    private static final ZoneId APP_ZONE = ZoneId.of("Asia/Shanghai");

    private static final String SYSTEM_PROMPT = """
            You are Universal Assistant, a desktop AI assistant.
            Answer in Chinese by default unless the user asks for another language.
            Be concise, practical, and honest about uncertainty.
            When search context is provided, use it and cite source links naturally.
            When weather tool context is provided, use it as the primary weather source and cite the source links naturally.
            For local file operations, explain the intended operation and remind the user that the desktop app must confirm writes.
            Format user-facing answers as valid GitHub Flavored Markdown.
            If using a table, put a blank line before and after it, keep the table header on its own line, and put the separator row immediately after the header.
            Never attach a heading, time label, or sentence to the same line as a Markdown table header.
            You must not guess your underlying model. If asked about the current model, use only the runtime model metadata provided by the backend.
            Never claim to be Claude, GPT, Gemini, or another model unless the runtime model metadata explicitly says so.
            """;

    private final ObjectProvider<ChatClient> chatClientProvider;
    private final SearchService searchService;
    private final ConversationService conversationService;
    private final AgentRunService agentRunService;
    private final MemoryService memoryService;
    private final WeatherTools weatherTools;
    private final WeatherQueryPlanner weatherQueryPlanner;
    private final PendingAgentActionService pendingAgentActionService;
    private final MessageBlockFactory messageBlockFactory;
    private final String dashScopeApiKey;
    private final int maxTokens;

    public ChatService(@Qualifier("deepSeekChatClient") ObjectProvider<ChatClient> chatClientProvider,
                       SearchService searchService,
                       ConversationService conversationService,
                       AgentRunService agentRunService,
                       MemoryService memoryService,
                       WeatherTools weatherTools,
                       WeatherQueryPlanner weatherQueryPlanner,
                       PendingAgentActionService pendingAgentActionService,
                       MessageBlockFactory messageBlockFactory,
                       @Value("${spring.ai.dashscope.api-key:}") String dashScopeApiKey,
                       @Value("${assistant.chat.max-tokens:2400}") int maxTokens) {
        this.chatClientProvider = chatClientProvider;
        this.searchService = searchService;
        this.conversationService = conversationService;
        this.agentRunService = agentRunService;
        this.memoryService = memoryService;
        this.weatherTools = weatherTools;
        this.weatherQueryPlanner = weatherQueryPlanner;
        this.pendingAgentActionService = pendingAgentActionService;
        this.messageBlockFactory = messageBlockFactory;
        this.dashScopeApiKey = dashScopeApiKey;
        this.maxTokens = maxTokens;
    }

    private record PreparedAgentContext(
            AgentRunContext runContext,
            AgentMode mode,
            boolean useSearch,
            WeatherReport weatherReport,
            List<SearchResult> sources,
            List<MemoryHit> memories,
            String plan
    ) {
    }

    private record SearchDecision(boolean needsSearch, String reason, String query) {
    }

    private record WeatherQueryResult(WeatherQuery query, WeatherReport report, String status) {
    }

    public ChatResponse chat(ChatRequest request) {
        String rawMessage = request == null ? "" : request.message();
        String selectedModel = resolveModel(request, rawMessage);
        if (!SUPPORTED_MODELS.containsKey(selectedModel)) {
            return new ChatResponse(
                    "暂不支持模型 " + selectedModel + "。当前可用模型：" + String.join("、", SUPPORTED_MODELS.keySet()) + "。",
                    false,
                    false,
                    DEFAULT_MODEL,
                    List.of()
            );
        }

        String message = removeLeadingModelCommand(rawMessage);
        if (!StringUtils.hasText(message)) {
            return new ChatResponse("请输入要咨询的问题。", false, isModelConfigured(), selectedModel, List.of());
        }

        Boolean requestedSearch = request == null ? null : request.realtimeSearch();
        ConversationEntity conversation = conversationService.getOrCreateConversation(request == null ? null : request.conversationId(), message);
        MessageEntity userMessage;
        List<ChatMessage> history;
        if (request != null && StringUtils.hasText(request.editMessageId())) {
            userMessage = conversationService.editUserMessageAndInvalidateAfter(conversation.getId().toString(), request.editMessageId(), message);
            history = conversationService.recentHistoryBefore(conversation.getId(), userMessage.getCreatedAt(), 16);
        } else {
            history = conversationService.recentHistory(conversation.getId(), 16);
            if (history.isEmpty() && request != null && request.history() != null) {
                history = request.history();
            }
            userMessage = conversationService.saveUserMessage(conversation, message);
        }
        String conversationSummary = conversationService.summaryText(conversation.getId()).orElse("");
        PendingAgentActionService.PendingActionResolution pendingResolution = pendingAgentActionService.resolve(conversation, userMessage, message);
        if (pendingResolution.rejected()) {
            AgentRunContext runContext = agentRunService.startRun(conversation, userMessage, AgentMode.DIRECT, message, selectedModel);
            addAgentStep(runContext, "thought", "处理待确认任务", "用户拒绝继续上一轮 pending action，关闭等待状态。", "completed", null);
            return persistChatResponse(
                    conversation,
                    pendingResolution.directAnswer(),
                    false,
                    isModelConfigured(),
                    selectedModel,
                    List.of(),
                    runContext,
                    message
            );
        }
        if (pendingResolution.confirmed()) {
            message = pendingResolution.effectiveMessage();
            requestedSearch = false;
        }
        if (shouldAnswerModelIdentity(message)) {
            AgentRunContext runContext = agentRunService.startRun(conversation, userMessage, AgentMode.DIRECT, message, selectedModel);
            addAgentStep(runContext, "thought", "识别模型身份问题", "用户询问当前底层模型，直接使用后端运行时模型元数据回答。", "completed", null);
            String answer = modelIdentityAnswer(selectedModel);
            return persistChatResponse(conversation, answer, false, isModelConfigured(), selectedModel, List.of(), runContext, message);
        }

        boolean modelConfigured = isModelConfigured();
        ChatClient chatClient = modelConfigured ? chatClientProvider.getIfAvailable() : null;
        boolean modelAvailable = modelConfigured && chatClient != null;
        PreparedAgentContext agentContext;
        try {
            agentContext = prepareAgentContext(
                    conversation,
                    userMessage,
                    message,
                    requestedSearch,
                    selectedModel,
                    history,
                    conversationSummary,
                    chatClient,
                    modelAvailable,
                    null
            );
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }

        if (agentContext.weatherReport() != null
                && !agentContext.weatherReport().available()
                && agentContext.weatherReport().sources().isEmpty()
                && agentContext.sources().isEmpty()) {
            return persistChatResponse(
                    conversation,
                    agentContext.weatherReport().summary(),
                    true,
                    modelAvailable,
                    selectedModel,
                    agentContext.sources(),
                    agentContext.runContext(),
                    message
            );
        }

        if (!modelAvailable) {
            String answer = fallbackAnswer(agentContext.weatherReport(), agentContext.sources(), false);
            addAgentStep(agentContext.runContext(), "final", "模型不可用，返回降级结果", answer, "completed", null);
            return persistChatResponse(conversation, answer, agentContext.useSearch(), false, selectedModel, agentContext.sources(), agentContext.runContext(), message);
        }

        try {
            String answer = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .options(DashScopeChatOptions.builder()
                            .model(selectedModel)
                            .temperature(0.5)
                            .maxToken(maxTokens)
                            .build())
                    .tools(weatherTools)
                    .user(buildUserPrompt(
                            message,
                            conversationSummary,
                            history,
                            agentContext.sources(),
                            agentContext.weatherReport(),
                            agentContext.memories(),
                            agentContext.plan(),
                            selectedModel
                    ))
                    .call()
                    .content();

            if (!StringUtils.hasText(answer)) {
                answer = fallbackAnswer(agentContext.weatherReport(), agentContext.sources(), true);
            }
            answer = completeIncompleteAnswerIfNeeded(answer, message, agentContext, false, null);
            answer = applyReflectionIfNeeded(
                    answer,
                    message,
                    agentContext,
                    chatClient,
                    selectedModel,
                    false,
                    null
            );

            return persistChatResponse(conversation, answer, agentContext.useSearch(), true, selectedModel, agentContext.sources(), agentContext.runContext(), message);
        } catch (RuntimeException ex) {
            String answer = "模型调用失败：" + ex.getMessage();
            if (!agentContext.sources().isEmpty()) {
                answer += "\n\n" + fallbackSearchSummary(agentContext.sources());
            }
            addAgentStep(agentContext.runContext(), "error", "模型调用失败", ex.getMessage(), "failed", null);
            return persistChatResponse(conversation, answer, agentContext.useSearch(), false, selectedModel, agentContext.sources(), agentContext.runContext(), message);
        }
    }

    public void streamChat(ChatRequest request, ChatStreamConsumer consumer) throws IOException {
        String rawMessage = request == null ? "" : request.message();
        String selectedModel = resolveModel(request, rawMessage);

        emit(consumer, ChatStreamEvent.status("thinking", "思考中"));
        if (request != null && StringUtils.hasText(request.agentRunId()) && StringUtils.hasText(request.toolDecision())) {
            continueAgentRunAfterToolDecision(request, selectedModel, consumer);
            return;
        }

        if (!SUPPORTED_MODELS.containsKey(selectedModel)) {
            emit(consumer, ChatStreamEvent.error("暂不支持模型 " + selectedModel + "。当前可用模型：" + String.join("、", SUPPORTED_MODELS.keySet()) + "。"));
            emit(consumer, ChatStreamEvent.done());
            return;
        }

        String message = removeLeadingModelCommand(rawMessage);
        if (!StringUtils.hasText(message)) {
            emit(consumer, ChatStreamEvent.error("请输入要咨询的问题。"));
            emit(consumer, ChatStreamEvent.done());
            return;
        }

        Boolean requestedSearch = request == null ? null : request.realtimeSearch();
        ConversationEntity conversation = conversationService.getOrCreateConversation(request == null ? null : request.conversationId(), message);
        MessageEntity userMessage;
        List<ChatMessage> history;
        if (request != null && StringUtils.hasText(request.editMessageId())) {
            userMessage = conversationService.editUserMessageAndInvalidateAfter(conversation.getId().toString(), request.editMessageId(), message);
            history = conversationService.recentHistoryBefore(conversation.getId(), userMessage.getCreatedAt(), 16);
        } else {
            history = conversationService.recentHistory(conversation.getId(), 16);
            if (history.isEmpty() && request != null && request.history() != null) {
                history = request.history();
            }
            userMessage = conversationService.saveUserMessage(conversation, message);
        }
        String conversationSummary = conversationService.summaryText(conversation.getId()).orElse("");
        PendingAgentActionService.PendingActionResolution pendingResolution = pendingAgentActionService.resolve(conversation, userMessage, message);
        if (pendingResolution.rejected()) {
            boolean modelAvailable = isModelConfigured();
            AgentRunContext runContext = agentRunService.startRun(conversation, userMessage, AgentMode.DIRECT, message, selectedModel);
            emit(consumer, ChatStreamEvent.meta(selectedModel, false, modelAvailable, List.of(), conversation.getId(), null, userMessage.getId(), runContext.run().getId()));
            addAgentStep(runContext, "thought", "处理待确认任务", "用户拒绝继续上一轮 pending action，关闭等待状态。", "completed", consumer);
            MessageEntity savedMessage = saveAssistantMessageAndFinalize(conversation, pendingResolution.directAnswer(), selectedModel, false, modelAvailable, List.of(), runContext, message);
            emit(consumer, ChatStreamEvent.meta(selectedModel, false, modelAvailable, List.of(), conversation.getId(), savedMessage.getId(), userMessage.getId(), runContext.run().getId()));
            emit(consumer, ChatStreamEvent.status("answering", "回答中"));
            String answerBlockId = beginMarkdownBlock(consumer);
            emitChunkedAnswerDelta(consumer, answerBlockId, pendingResolution.directAnswer());
            endMarkdownBlock(consumer, answerBlockId);
            emit(consumer, ChatStreamEvent.done());
            return;
        }
        if (pendingResolution.confirmed()) {
            message = pendingResolution.effectiveMessage();
            requestedSearch = false;
        }
        if (shouldAnswerModelIdentity(message)) {
            boolean modelAvailable = isModelConfigured();
            AgentRunContext runContext = agentRunService.startRun(conversation, userMessage, AgentMode.DIRECT, message, selectedModel);
            emit(consumer, ChatStreamEvent.meta(selectedModel, false, modelAvailable, List.of(), conversation.getId(), null, userMessage.getId(), runContext.run().getId()));
            addAgentStep(runContext, "thought", "识别模型身份问题", "用户询问当前底层模型，直接使用后端运行时模型元数据回答。", "completed", consumer);
            String answer = modelIdentityAnswer(selectedModel);
            MessageEntity savedMessage = saveAssistantMessageAndFinalize(conversation, answer, selectedModel, false, modelAvailable, List.of(), runContext, message);
            emit(consumer, ChatStreamEvent.meta(selectedModel, false, modelAvailable, List.of(), conversation.getId(), savedMessage.getId(), userMessage.getId(), runContext.run().getId()));
            emit(consumer, ChatStreamEvent.status("answering", "回答中"));
            String answerBlockId = beginMarkdownBlock(consumer);
            emitChunkedAnswerDelta(consumer, answerBlockId, answer);
            endMarkdownBlock(consumer, answerBlockId);
            emit(consumer, ChatStreamEvent.done());
            return;
        }

        boolean modelConfigured = isModelConfigured();
        ChatClient chatClient = modelConfigured ? chatClientProvider.getIfAvailable() : null;
        boolean modelAvailable = modelConfigured && chatClient != null;
        PreparedAgentContext agentContext = prepareAgentContext(
                conversation,
                userMessage,
                message,
                requestedSearch,
                selectedModel,
                history,
                conversationSummary,
                chatClient,
                modelAvailable,
                consumer
        );
        emit(consumer, ChatStreamEvent.meta(
                selectedModel,
                agentContext.useSearch(),
                modelAvailable,
                agentContext.sources(),
                conversation.getId(),
                null,
                userMessage.getId(),
                agentContext.runContext().run().getId()
        ));
        if ("waiting_confirmation".equals(agentContext.runContext().run().getStatus())) {
            emit(consumer, ChatStreamEvent.done());
            return;
        }
        emit(consumer, ChatStreamEvent.status("answering", "回答中"));
        String answerBlockId = beginMarkdownBlock(consumer);

        if (agentContext.weatherReport() != null
                && !agentContext.weatherReport().available()
                && agentContext.weatherReport().sources().isEmpty()
                && agentContext.sources().isEmpty()) {
            MessageEntity savedMessage = saveAssistantMessageAndFinalize(
                    conversation,
                    agentContext.weatherReport().summary(),
                    selectedModel,
                    true,
                    modelAvailable,
                    agentContext.sources(),
                    agentContext.runContext(),
                    message
            );
            emit(consumer, ChatStreamEvent.meta(selectedModel, true, modelAvailable, agentContext.sources(), conversation.getId(), savedMessage.getId(), userMessage.getId(), agentContext.runContext().run().getId()));
            emitChunkedAnswerDelta(consumer, answerBlockId, agentContext.weatherReport().summary());
            endMarkdownBlock(consumer, answerBlockId);
            emit(consumer, ChatStreamEvent.done());
            return;
        }

        if (!modelAvailable) {
            String answer = fallbackAnswer(agentContext.weatherReport(), agentContext.sources(), false);
            addAgentStep(agentContext.runContext(), "final", "模型不可用，返回降级结果", answer, "completed", consumer);
            MessageEntity savedMessage = saveAssistantMessageAndFinalize(
                    conversation,
                    answer,
                    selectedModel,
                    agentContext.useSearch(),
                    false,
                    agentContext.sources(),
                    agentContext.runContext(),
                    message
            );
            emit(consumer, ChatStreamEvent.meta(selectedModel, agentContext.useSearch(), false, agentContext.sources(), conversation.getId(), savedMessage.getId(), userMessage.getId(), agentContext.runContext().run().getId()));
            emitChunkedAnswerDelta(consumer, answerBlockId, answer);
            endMarkdownBlock(consumer, answerBlockId);
            emit(consumer, ChatStreamEvent.done());
            return;
        }

        emit(consumer, ChatStreamEvent.meta(
                selectedModel,
                agentContext.useSearch(),
                modelAvailable,
                agentContext.sources(),
                conversation.getId(),
                null,
                userMessage.getId(),
                agentContext.runContext().run().getId()
        ));
        agentRunService.updateProgress(agentContext.runContext(), "", agentContext.useSearch(), modelAvailable, agentContext.sources());

        StringBuilder streamedAnswer = new StringBuilder();
        int[] lastSavedLength = {0};
        long[] lastSavedAt = {System.currentTimeMillis()};
        try {
            chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .options(DashScopeChatOptions.builder()
                            .model(selectedModel)
                            .temperature(0.5)
                            .maxToken(maxTokens)
                            .build())
                    .tools(weatherTools)
                    .user(buildUserPrompt(
                            message,
                            conversationSummary,
                            history,
                            agentContext.sources(),
                            agentContext.weatherReport(),
                            agentContext.memories(),
                            agentContext.plan(),
                            selectedModel
                    ))
                    .stream()
                    .chatResponse()
                    .map(this::extractStreamContent)
                    .filter(StringUtils::hasText)
                    .doOnNext(content -> {
                        if (agentRunService.shouldStop(agentContext.runContext().run().getId())) {
                            throw new AgentRunCancelledException();
                        }
                        streamedAnswer.append(content);
                        persistPartialIfNeeded(agentContext.runContext(), streamedAnswer, agentContext.useSearch(), modelAvailable, agentContext.sources(), lastSavedLength, lastSavedAt);
                        try {
                            emitAnswerDelta(consumer, answerBlockId, content);
                        } catch (IOException ex) {
                            throw new UncheckedIOException(ex);
                        }
                    })
                    .blockLast();

            if (agentRunService.shouldStop(agentContext.runContext().run().getId())) {
                saveCancelledPartial(conversation, streamedAnswer.toString(), selectedModel, agentContext.useSearch(), true, agentContext.sources(), agentContext.runContext(), message);
                return;
            }
            agentRunService.updateProgress(agentContext.runContext(), streamedAnswer.toString(), agentContext.useSearch(), true, agentContext.sources());

            if (!StringUtils.hasText(streamedAnswer.toString())) {
                String fallbackAnswer = fallbackAnswer(agentContext.weatherReport(), agentContext.sources(), true);
                if (StringUtils.hasText(fallbackAnswer)) {
                    MessageEntity savedMessage = saveAssistantMessageAndFinalize(
                            conversation,
                            fallbackAnswer,
                            selectedModel,
                            agentContext.useSearch(),
                            true,
                            agentContext.sources(),
                            agentContext.runContext(),
                            message
                    );
                    emit(consumer, ChatStreamEvent.meta(selectedModel, agentContext.useSearch(), true, agentContext.sources(), conversation.getId(), savedMessage.getId(), userMessage.getId(), agentContext.runContext().run().getId()));
                    emitChunkedAnswerDelta(consumer, answerBlockId, fallbackAnswer);
                } else {
                    String errorMessage = "没有收到模型返回内容。";
                    agentRunService.failRun(agentContext.runContext(), errorMessage, "");
                    emit(consumer, ChatStreamEvent.error("没有收到模型返回内容。"));
                }
            } else {
                String completedAnswer = completeIncompleteAnswerIfNeeded(
                        streamedAnswer.toString(),
                        message,
                        agentContext,
                        true,
                        consumer
                );
                agentRunService.updateProgress(agentContext.runContext(), completedAnswer, agentContext.useSearch(), true, agentContext.sources());
                String finalAnswer = applyReflectionIfNeeded(
                        completedAnswer,
                        message,
                        agentContext,
                        chatClient,
                        selectedModel,
                        true,
                        consumer
                );
                agentRunService.updateProgress(agentContext.runContext(), finalAnswer, agentContext.useSearch(), true, agentContext.sources());
                MessageEntity savedMessage = saveAssistantMessageAndFinalize(
                        conversation,
                        finalAnswer,
                        selectedModel,
                        agentContext.useSearch(),
                        true,
                        agentContext.sources(),
                        agentContext.runContext(),
                        message
                );
                emit(consumer, ChatStreamEvent.meta(selectedModel, agentContext.useSearch(), true, agentContext.sources(), conversation.getId(), savedMessage.getId(), userMessage.getId(), agentContext.runContext().run().getId()));
            }

            endMarkdownBlock(consumer, answerBlockId);
            emit(consumer, ChatStreamEvent.done());
        } catch (AgentRunCancelledException ex) {
            saveCancelledPartial(conversation, streamedAnswer.toString(), selectedModel, agentContext.useSearch(), modelAvailable, agentContext.sources(), agentContext.runContext(), message);
        } catch (UncheckedIOException ex) {
            saveCancelledPartial(conversation, streamedAnswer.toString(), selectedModel, agentContext.useSearch(), modelAvailable, agentContext.sources(), agentContext.runContext(), message);
            throw ex.getCause();
        } catch (RuntimeException ex) {
            String fallback = fallbackAnswer(agentContext.weatherReport(), agentContext.sources(), true);
            String partial = streamedAnswer.toString();
            String answer = StringUtils.hasText(partial) ? partial : fallback;
            if (StringUtils.hasText(answer)) {
                addAgentStep(
                        agentContext.runContext(),
                        "warning",
                        "模型调用中断，返回降级结果",
                        "模型流式调用中断：" + ex.getMessage() + "。已使用已有回答、天气工具或检索结果完成本次回复。",
                        "degraded",
                        consumer
                );
                MessageEntity savedMessage = saveAssistantMessageAndFinalize(
                        conversation,
                        answer,
                        selectedModel,
                        agentContext.useSearch(),
                        false,
                        agentContext.sources(),
                        agentContext.runContext(),
                        message
                );
                emit(consumer, ChatStreamEvent.meta(selectedModel, agentContext.useSearch(), false, agentContext.sources(), conversation.getId(), savedMessage.getId(), userMessage.getId(), agentContext.runContext().run().getId()));
                if (!StringUtils.hasText(partial)) {
                    emitChunkedAnswerDelta(consumer, answerBlockId, answer);
                }
            } else {
                addAgentStep(agentContext.runContext(), "error", "模型调用失败", ex.getMessage(), "failed", consumer);
                agentRunService.failRun(agentContext.runContext(), "模型调用失败：" + ex.getMessage(), partial);
                emit(consumer, ChatStreamEvent.error("模型调用失败：" + ex.getMessage()));
            }
            endMarkdownBlock(consumer, answerBlockId);
            emit(consumer, ChatStreamEvent.done());
        }
    }

    public ChatResponse cancelAgentRun(String agentRunId, CancelAgentRunRequest request) {
        UUID runId = parseUuid(agentRunId);
        agentRunService.requestCancel(runId);
        AgentRunContext context = agentRunService.findRun(runId)
                .map(agentRunService::contextForRun)
                .orElseThrow();
        if (agentRunService.isCancelled(runId)) {
            return new ChatResponse(
                    context.run().getFinalAnswer(),
                    messageBlockFactory.assistantBlocks(
                            context.run().getFinalAnswer(),
                            context,
                            request == null || request.sources() == null ? List.of() : request.sources(),
                            "completed"
                    ),
                    request != null && Boolean.TRUE.equals(request.realtimeSearchUsed()),
                    request == null || request.modelAvailable() == null || request.modelAvailable(),
                    request == null || !StringUtils.hasText(request.model()) ? context.run().getModel() : request.model(),
                    request == null || request.sources() == null ? List.of() : request.sources(),
                    context.run().getConversation().getId(),
                    context.run().getAssistantMessageId(),
                    context.run().getId(),
                    context.steps()
            );
        }
        String partialAnswer = request == null || !StringUtils.hasText(request.partialAnswer())
                ? (StringUtils.hasText(context.run().getPartialAnswer()) ? context.run().getPartialAnswer() : CANCELLED_ANSWER)
                : request.partialAnswer();
        String model = request == null || !StringUtils.hasText(request.model()) ? context.run().getModel() : request.model();
        boolean realtimeSearchUsed = request != null && Boolean.TRUE.equals(request.realtimeSearchUsed());
        boolean modelAvailable = request == null || request.modelAvailable() == null || request.modelAvailable();
        List<SearchResult> sources = request == null || request.sources() == null ? List.of() : request.sources();

        MessageEntity savedMessage = null;
        if (agentRunService.cancelRun(context.run(), partialAnswer)) {
            savedMessage = conversationService.saveAssistantMessage(
                    context.run().getConversation(),
                    partialAnswer,
                    model,
                    realtimeSearchUsed,
                    modelAvailable,
                    sources,
                    "cancelled",
                    messageBlockFactory.assistantBlocks(partialAnswer, context, sources, "cancelled")
            );
            agentRunService.attachAssistantMessage(context, savedMessage);
        }
        return new ChatResponse(
                partialAnswer,
                savedMessage == null
                        ? messageBlockFactory.assistantBlocks(partialAnswer, context, sources, "cancelled")
                        : conversationService.readMessageBlocks(savedMessage, context, sources),
                realtimeSearchUsed,
                modelAvailable,
                model,
                sources,
                context.run().getConversation().getId(),
                savedMessage == null ? context.run().getAssistantMessageId() : savedMessage.getId(),
                context.run().getId(),
                context.steps()
        );
    }

    private void continueAgentRunAfterToolDecision(ChatRequest request, String selectedModel, ChatStreamConsumer consumer) throws IOException {
        if (!SUPPORTED_MODELS.containsKey(selectedModel)) {
            emit(consumer, ChatStreamEvent.error("暂不支持模型 " + selectedModel + "。当前可用模型：" + String.join("、", SUPPORTED_MODELS.keySet()) + "。"));
            emit(consumer, ChatStreamEvent.done());
            return;
        }

        UUID runId = parseUuid(request.agentRunId());
        AgentRunContext context = agentRunService.findRun(runId)
                .map(agentRunService::contextForRun)
                .orElseThrow();
        ConversationEntity conversation = context.run().getConversation();
        String message = context.run().getUserGoal();
        if (context.run().getAssistantMessageId() != null && StringUtils.hasText(context.run().getFinalAnswer())) {
            emit(consumer, ChatStreamEvent.meta(
                    selectedModel,
                    Boolean.TRUE.equals(context.run().getRealtimeSearchUsed()),
                    context.run().getModelAvailable() == null || context.run().getModelAvailable(),
                    List.of(),
                    conversation.getId(),
                    context.run().getAssistantMessageId(),
                    context.run().getUserMessageId(),
                    context.run().getId()
            ));
            String answerBlockId = beginMarkdownBlock(consumer);
            emitChunkedAnswerDelta(consumer, answerBlockId, context.run().getFinalAnswer());
            endMarkdownBlock(consumer, answerBlockId);
            emit(consumer, ChatStreamEvent.done());
            return;
        }
        boolean approved = "approved".equalsIgnoreCase(request.toolDecision()) || "allow".equalsIgnoreCase(request.toolDecision());
        boolean denied = "denied".equalsIgnoreCase(request.toolDecision()) || "reject".equalsIgnoreCase(request.toolDecision());
        if (!approved && !denied) {
            emit(consumer, ChatStreamEvent.error("未知的工具确认结果：" + request.toolDecision()));
            emit(consumer, ChatStreamEvent.done());
            return;
        }

        Map<String, Object> pendingInput = agentRunService.readPendingToolInput(context.run());
        agentRunService.markRunning(context);
        addAgentStep(
                context,
                "user_decision",
                approved ? "用户允许实时检索" : "用户拒绝实时检索",
                approved ? "用户确认允许 Agent 联网搜索。" : "用户选择不联网，后续回答不能编造实时信息。",
                "completed",
                consumer
        );

        List<SearchResult> sources = new ArrayList<>();
        if (approved) {
            String query = String.valueOf(pendingInput.getOrDefault("query", message));
            emit(consumer, ChatStreamEvent.status("searching", "检索中"));
            AgentStepResponse searchStep = addAgentStep(context, "action", "调用实时检索", "query=" + query, "completed", consumer);
            Instant startedAt = Instant.now();
            sources.addAll(searchSafely(query, 5));
            emitSourcesMeta(context, true, runModelAvailable(context), sources, consumer);
            agentRunService.recordToolCall(
                    context,
                    searchStep,
                    "web_search",
                    Map.of("query", query, "limit", 5),
                    fallbackSearchSummary(sources),
                    "completed",
                    startedAt
            );
            addAgentStep(context, "observation", "实时检索结果", fallbackSearchSummary(sources), "completed", consumer);
        }

        boolean modelConfigured = isModelConfigured();
        ChatClient chatClient = modelConfigured ? chatClientProvider.getIfAvailable() : null;
        boolean modelAvailable = modelConfigured && chatClient != null;
        List<MemoryHit> memories = memoryService.retrieveRelevantMemories(message, conversation.getId());
        String conversationSummary = conversationService.summaryText(conversation.getId()).orElse("");
        List<ChatMessage> history = conversationService.recentHistory(conversation.getId(), 16);
        emit(consumer, ChatStreamEvent.meta(selectedModel, approved, modelAvailable, sources, conversation.getId(), null, context.run().getUserMessageId(), context.run().getId()));
        emit(consumer, ChatStreamEvent.status("answering", "回答中"));
        String answerBlockId = beginMarkdownBlock(consumer);
        agentRunService.updateProgress(context, "", approved, modelAvailable, sources);

        if (!modelAvailable) {
            String answer = approved
                    ? fallbackAnswer(null, sources, false)
                    : "你已选择不联网检索。当前后端还没有可用模型，因此无法在不联网的情况下生成完整回答。";
            MessageEntity savedMessage = saveAssistantMessageAndFinalize(conversation, answer, selectedModel, approved, false, sources, context, message);
            emit(consumer, ChatStreamEvent.meta(selectedModel, approved, false, sources, conversation.getId(), savedMessage.getId(), context.run().getUserMessageId(), context.run().getId()));
            emitChunkedAnswerDelta(consumer, answerBlockId, answer);
            endMarkdownBlock(consumer, answerBlockId);
            emit(consumer, ChatStreamEvent.done());
            return;
        }

        String promptMessage = approved
                ? message
                : message + "\n\n用户拒绝了实时检索。请不要编造当前、最新、实时信息；如果问题依赖实时信息，请明确说明无法确认。";
        StringBuilder streamedAnswer = new StringBuilder();
        int[] lastSavedLength = {0};
        long[] lastSavedAt = {System.currentTimeMillis()};
        try {
            chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .options(DashScopeChatOptions.builder()
                            .model(selectedModel)
                            .temperature(0.5)
                            .maxToken(maxTokens)
                            .build())
                    .tools(weatherTools)
                    .user(buildUserPrompt(promptMessage, conversationSummary, history, sources, null, memories, "", selectedModel))
                    .stream()
                    .chatResponse()
                    .map(this::extractStreamContent)
                    .filter(StringUtils::hasText)
                    .doOnNext(content -> {
                        if (agentRunService.shouldStop(context.run().getId())) {
                            throw new AgentRunCancelledException();
                        }
                        streamedAnswer.append(content);
                        persistPartialIfNeeded(context, streamedAnswer, approved, modelAvailable, sources, lastSavedLength, lastSavedAt);
                        try {
                            emitAnswerDelta(consumer, answerBlockId, content);
                        } catch (IOException ex) {
                            throw new UncheckedIOException(ex);
                        }
                    })
                    .blockLast();

            if (agentRunService.shouldStop(context.run().getId())) {
                saveCancelledPartial(conversation, streamedAnswer.toString(), selectedModel, approved, modelAvailable, sources, context, message);
                return;
            }
            agentRunService.updateProgress(context, streamedAnswer.toString(), approved, true, sources);

            String answer = StringUtils.hasText(streamedAnswer.toString())
                    ? streamedAnswer.toString()
                    : fallbackAnswer(null, sources, true);
            answer = applyReflectionIfNeeded(answer, message, new PreparedAgentContext(context, context.mode(), approved, null, sources, memories, ""), chatClient, selectedModel, true, consumer);
            agentRunService.updateProgress(context, answer, approved, true, sources);
            MessageEntity savedMessage = saveAssistantMessageAndFinalize(conversation, answer, selectedModel, approved, true, sources, context, message);
            emit(consumer, ChatStreamEvent.meta(selectedModel, approved, true, sources, conversation.getId(), savedMessage.getId(), context.run().getUserMessageId(), context.run().getId()));
            endMarkdownBlock(consumer, answerBlockId);
            emit(consumer, ChatStreamEvent.done());
        } catch (AgentRunCancelledException ex) {
            saveCancelledPartial(conversation, streamedAnswer.toString(), selectedModel, approved, modelAvailable, sources, context, message);
        } catch (UncheckedIOException ex) {
            saveCancelledPartial(conversation, streamedAnswer.toString(), selectedModel, approved, modelAvailable, sources, context, message);
            throw ex.getCause();
        } catch (RuntimeException ex) {
            String fallback = approved ? fallbackAnswer(null, sources, true) : "";
            String partial = streamedAnswer.toString();
            String answer = StringUtils.hasText(partial) ? partial : fallback;
            if (StringUtils.hasText(answer)) {
                addAgentStep(
                        context,
                        "warning",
                        "模型调用中断，返回降级结果",
                        "模型流式调用中断：" + ex.getMessage() + "。已使用已有回答或检索结果完成本次回复。",
                        "degraded",
                        consumer
                );
                MessageEntity savedMessage = saveAssistantMessageAndFinalize(conversation, answer, selectedModel, approved, false, sources, context, message);
                emit(consumer, ChatStreamEvent.meta(selectedModel, approved, false, sources, conversation.getId(), savedMessage.getId(), context.run().getUserMessageId(), context.run().getId()));
                if (!StringUtils.hasText(partial)) {
                    emitChunkedAnswerDelta(consumer, answerBlockId, answer);
                }
            } else {
                addAgentStep(context, "error", "模型调用失败", ex.getMessage(), "failed", consumer);
                agentRunService.failRun(context, "模型调用失败：" + ex.getMessage(), partial);
                emit(consumer, ChatStreamEvent.error("模型调用失败：" + ex.getMessage()));
            }
            endMarkdownBlock(consumer, answerBlockId);
            emit(consumer, ChatStreamEvent.done());
        }
    }

    private PreparedAgentContext prepareAgentContext(ConversationEntity conversation,
                                                     MessageEntity userMessage,
                                                     String message,
                                                     Boolean requestedSearch,
                                                     String selectedModel,
                                                     List<ChatMessage> history,
                                                     String conversationSummary,
                                                     ChatClient chatClient,
                                                     boolean modelAvailable,
                                                     ChatStreamConsumer consumer) throws IOException {
        WeatherPlan weatherPlan = weatherQueryPlanner.plan(message, history, conversationSummary);
        boolean weatherIntent = shouldUseWeatherTool(message) || weatherPlan.weatherIntent();
        SearchDecision searchDecision = decideSearchNeed(message, history, chatClient, selectedModel);
        boolean useSearch = Boolean.TRUE.equals(requestedSearch) || (!weatherIntent && searchDecision.needsSearch());
        AgentMode mode = selectAgentMode(message, weatherIntent, useSearch);
        AgentRunContext runContext = agentRunService.startRun(conversation, userMessage, mode, message, selectedModel);
        agentRunService.updateProgress(runContext, "", useSearch, modelAvailable, List.of());
        if (consumer != null) {
            emit(consumer, ChatStreamEvent.meta(selectedModel, useSearch, modelAvailable, List.of(), conversation.getId(), null, userMessage.getId(), runContext.run().getId()));
        }

        addAgentStep(
                runContext,
                "thought",
                "选择执行模式",
                "当前模式：" + mode.name() + "；最大工具步数：" + runContext.run().getMaxSteps() + "；反思上限：" + runContext.run().getReflectionLimit() + "。",
                "completed",
                consumer
        );

        List<SearchResult> sources = new ArrayList<>();
        List<MemoryHit> memories = memoryService.retrieveRelevantMemories(message, conversation.getId());
        if (!memories.isEmpty()) {
            AgentStepResponse memoryStep = addAgentStep(
                    runContext,
                    "memory",
                    "检索长期记忆",
                    summarizeMemories(memories),
                    "completed",
                    consumer
            );
            agentRunService.recordToolCall(
                    runContext,
                    memoryStep,
                    "memory_search",
                    Map.of("query", message, "limit", memories.size()),
                    summarizeMemories(memories),
                    "completed",
                    Instant.now()
            );
        }

        String plan = "";
        if (mode == AgentMode.PLAN_AND_SOLVE) {
            if (consumer != null) {
                emit(consumer, ChatStreamEvent.status("planning", "规划中"));
            }
            plan = generatePlan(message, history, memories, chatClient, selectedModel);
            addAgentStep(runContext, "plan", "任务计划", plan, "completed", consumer);
        }

        WeatherReport weatherReport = null;
        if (mode == AgentMode.REACT) {
            if (consumer != null) {
                emit(consumer, ChatStreamEvent.status("acting", "调用工具中"));
            }
            weatherReport = runReactToolLoop(runContext, message, requestedSearch, weatherIntent, weatherPlan, useSearch, searchDecision, sources, consumer);
        } else if (useSearch) {
            if (consumer != null) {
                emit(consumer, ChatStreamEvent.status("searching", "检索中"));
            }
            AgentStepResponse searchStep = addAgentStep(runContext, "action", "调用实时检索", "query=" + message, "completed", consumer);
            Instant startedAt = Instant.now();
            List<SearchResult> searchResults = searchSafely(message, 5);
            sources.addAll(searchResults);
            emitSourcesMeta(runContext, true, modelAvailable, sources, consumer);
            agentRunService.recordToolCall(
                    runContext,
                    searchStep,
                    "web_search",
                    Map.of("query", message, "limit", 5),
                    fallbackSearchSummary(searchResults),
                    "completed",
                    startedAt
            );
            addAgentStep(runContext, "observation", "实时检索结果", fallbackSearchSummary(searchResults), "completed", consumer);
        }

        if (mode == AgentMode.DIRECT) {
            addAgentStep(runContext, "plan", "直接回答", "问题不需要拆解或调用工具，直接结合会话上下文回答。", "completed", consumer);
        }

        List<SearchResult> finalSources = deduplicateSources(sources);
        agentRunService.updateProgress(runContext, null, useSearch, modelAvailable, finalSources);
        return new PreparedAgentContext(
                runContext,
                mode,
                useSearch,
                weatherReport,
                finalSources,
                memories,
                plan
        );
    }

    private SearchDecision decideSearchNeed(String message,
                                            List<ChatMessage> history,
                                            ChatClient chatClient,
                                            String selectedModel) {
        boolean keywordDecision = shouldUseSearch(message, false);
        if (chatClient != null) {
            try {
                String decision = chatClient.prompt()
                        .system("""
                                Decide whether a desktop assistant needs real-time web search before answering.
                                Return one short line in this exact format:
                                SEARCH: yes/no | REASON: Chinese reason | QUERY: suggested search query
                                Say yes for current weather fallback, breaking news, current prices, latest versions, schedules, laws/policies, sports scores, exchange rates, or anything likely changed recently.
                                Say no for stable knowledge, coding explanation, writing, summarization, or local file operations.
                                """)
                        .options(DashScopeChatOptions.builder()
                                .model(selectedModel)
                                .temperature(0.1)
                                .maxToken(240)
                                .build())
                        .user(buildSearchDecisionPrompt(message, history))
                        .call()
                        .content();
                SearchDecision parsed = parseSearchDecision(decision, message);
                if (parsed != null) {
                    return parsed;
                }
            } catch (RuntimeException ignored) {
                // Fall back to deterministic keyword detection.
            }
        }

        return new SearchDecision(
                keywordDecision,
                keywordDecision ? "问题包含实时、最新、天气、价格、新闻等可能变化的信息。" : "问题更像稳定知识或普通对话，不需要实时检索。",
                message
        );
    }

    private String buildSearchDecisionPrompt(String message, List<ChatMessage> history) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Current date: ")
                .append(LocalDate.now(APP_ZONE))
                .append(" (Asia/Shanghai)\n\n");
        if (history != null && !history.isEmpty()) {
            prompt.append("Recent conversation:\n");
            history.stream()
                    .skip(Math.max(0, history.size() - 4))
                    .forEach(item -> prompt.append("- ")
                            .append(item.role())
                            .append(": ")
                            .append(trimForPrompt(item.content(), 220))
                            .append('\n'));
            prompt.append('\n');
        }
        prompt.append("User question:\n").append(message);
        return prompt.toString();
    }

    private SearchDecision parseSearchDecision(String decision, String fallbackQuery) {
        if (!StringUtils.hasText(decision)) {
            return null;
        }

        String lower = decision.toLowerCase(Locale.ROOT);
        Matcher searchMatcher = Pattern.compile("search\\s*[:：]\\s*(yes|no)", Pattern.CASE_INSENSITIVE).matcher(lower);
        if (!searchMatcher.find()) {
            return null;
        }
        boolean needsSearch = "yes".equalsIgnoreCase(searchMatcher.group(1));

        String reason = extractDecisionField(decision, "REASON");
        String query = extractDecisionField(decision, "QUERY");
        return new SearchDecision(
                needsSearch,
                StringUtils.hasText(reason) ? reason : (needsSearch ? "Agent 判断需要实时信息。" : "Agent 判断不需要实时信息。"),
                StringUtils.hasText(query) ? query : fallbackQuery
        );
    }

    private String extractDecisionField(String decision, String field) {
        Pattern pattern = Pattern.compile(field + "\\s*[:：]\\s*([^|\\n]+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(decision);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    private void requestSearchConfirmation(AgentRunContext runContext,
                                           SearchDecision searchDecision,
                                           ChatStreamConsumer consumer) throws IOException {
        AgentStepResponse step = addAgentStep(
                runContext,
                "tool_confirmation",
                "等待实时检索确认",
                searchDecision.reason(),
                "waiting",
                consumer
        );
        Map<String, String> input = Map.of(
                "query", StringUtils.hasText(searchDecision.query()) ? searchDecision.query() : runContext.run().getUserGoal(),
                "reason", searchDecision.reason()
        );
        agentRunService.markWaitingForTool(runContext, "web_search", input);
        agentRunService.recordToolCall(runContext, step, "web_search", input, "等待用户确认", "waiting", Instant.now());
        emit(consumer, ChatStreamEvent.status("waiting_confirmation", "等待确认"));
        emit(consumer, ChatStreamEvent.toolConfirmationRequired(
                runContext.run().getId(),
                "web_search",
                input.get("query"),
                searchDecision.reason()
        ));
    }

    private WeatherReport runReactToolLoop(AgentRunContext runContext,
                                           String message,
                                           Boolean requestedSearch,
                                           boolean weatherIntent,
                                           WeatherPlan weatherPlan,
                                           boolean useSearch,
                                           SearchDecision searchDecision,
                                           List<SearchResult> sources,
                                           ChatStreamConsumer consumer) throws IOException {
        int actionSteps = 0;
        int maxSteps = runContext.run().getMaxSteps();
        WeatherReport weatherReport = null;
        WeatherPlan resolvedWeatherPlan = weatherPlan == null ? WeatherPlan.empty() : weatherPlan;

        addAgentStep(
                runContext,
                "thought",
                "ReAct 思考",
                "根据问题判断是否需要天气工具、实时检索或降级检索；达到最大步数后停止继续调用工具。",
                "completed",
                consumer
        );

        if (weatherIntent) {
            addAgentStep(
                    runContext,
                    "thought",
                    "解析天气查询",
                    resolvedWeatherPlan.stepSummary(),
                    resolvedWeatherPlan.needsClarification() ? "waiting" : "completed",
                    consumer
            );
            if (resolvedWeatherPlan.needsClarification()) {
                return WeatherReport.unavailable("", resolvedWeatherPlan.clarificationQuestion(), List.of());
            }
        }

        if (weatherIntent && !resolvedWeatherPlan.queries().isEmpty() && actionSteps < maxSteps) {
            actionSteps++;
            AgentStepResponse weatherStep = addAgentStep(
                    runContext,
                    "action",
                    "批量调用天气工具",
                    resolvedWeatherPlan.queries().stream()
                            .map(WeatherQuery::callText)
                            .reduce((left, right) -> left + "\n" + right)
                            .orElse("没有可执行的天气查询。"),
                    "completed",
                    consumer
            );
            List<WeatherQueryResult> results = new ArrayList<>();
            for (WeatherQuery query : resolvedWeatherPlan.queries()) {
                Instant startedAt = Instant.now();
                WeatherReport report = weatherTools.getWeatherReport(query.resolvedLocation(), query.targetDate());
                String status = weatherToolStatus(report);
                sources.addAll(report.sources());
                results.add(new WeatherQueryResult(query, report, status));
                agentRunService.recordToolCall(
                        runContext,
                        weatherStep,
                        "get_weather",
                        Map.of(
                                "location", query.resolvedLocation(),
                                "date", query.targetDate(),
                                "source", query.source(),
                                "locationText", query.locationText()
                        ),
                        report.context(),
                        status,
                        startedAt
                );
                if ("cancelled".equals(status)) {
                    break;
                }
            }
            weatherReport = aggregateWeatherReports(resolvedWeatherPlan, results);
            addAgentStep(
                    runContext,
                    "observation",
                    "天气工具结果",
                    StringUtils.hasText(weatherReport.context()) ? weatherReport.context() : weatherReport.summary(),
                    weatherReport.available() ? "completed" : weatherToolStatus(weatherReport),
                    consumer
            );
        }

        boolean manualSearchRequested = Boolean.TRUE.equals(requestedSearch);
        boolean shouldRunSearch = manualSearchRequested || (searchDecision.needsSearch() && !weatherIntent);
        if (shouldRunSearch && actionSteps < maxSteps) {
            if (!manualSearchRequested && consumer != null) {
                requestSearchConfirmation(runContext, searchDecision, consumer);
                return weatherReport;
            }
            actionSteps++;
            AgentStepResponse searchStep = addAgentStep(runContext, "action", "调用实时检索", "query=" + message, "completed", consumer);
            Instant startedAt = Instant.now();
            List<SearchResult> searchResults = searchSafely(message, 5);
            sources.addAll(searchResults);
            emitSourcesMeta(runContext, true, runModelAvailable(runContext), sources, consumer);
            agentRunService.recordToolCall(
                    runContext,
                    searchStep,
                    "web_search",
                    Map.of("query", message, "limit", 5),
                    fallbackSearchSummary(searchResults),
                    "completed",
                    startedAt
            );
            addAgentStep(runContext, "observation", "实时检索结果", fallbackSearchSummary(searchResults), "completed", consumer);
        }

        if (shouldSearchWeatherFallback(weatherIntent, resolvedWeatherPlan, weatherReport) && actionSteps < maxSteps) {
            String fallbackQuery = weatherFallbackQuery(resolvedWeatherPlan, message);
            if (!Boolean.TRUE.equals(requestedSearch) && consumer != null) {
                SearchDecision fallbackDecision = new SearchDecision(true, "天气服务不可用，需要联网检索天气信息。", fallbackQuery);
                requestSearchConfirmation(runContext, fallbackDecision, consumer);
                return weatherReport;
            }
            actionSteps++;
            AgentStepResponse fallbackStep = addAgentStep(
                    runContext,
                    "action",
                    "天气降级检索",
                    "天气服务不可用，改用实时检索查找天气信息。",
                    "completed",
                    consumer
            );
            Instant startedAt = Instant.now();
            List<SearchResult> fallbackResults = searchSafely(fallbackQuery, 5);
            sources.addAll(fallbackResults);
            emitSourcesMeta(runContext, true, runModelAvailable(runContext), sources, consumer);
            agentRunService.recordToolCall(
                    runContext,
                    fallbackStep,
                    "web_search",
                    Map.of("query", fallbackQuery, "reason", "weather_fallback", "limit", 5),
                    fallbackSearchSummary(fallbackResults),
                    "completed",
                    startedAt
            );
            addAgentStep(runContext, "observation", "天气降级检索结果", fallbackSearchSummary(fallbackResults), "completed", consumer);
        }

        if (actionSteps >= maxSteps) {
            addAgentStep(runContext, "limit", "达到最大工具步数", "已达到 max_steps=" + maxSteps + "，停止继续调用工具。", "completed", consumer);
        }

        return weatherReport;
    }

    private AgentMode selectAgentMode(String message, boolean weatherIntent, boolean useSearch) {
        if (weatherIntent || useSearch) {
            return AgentMode.REACT;
        }
        if (isComplexTask(message)) {
            return AgentMode.PLAN_AND_SOLVE;
        }
        return AgentMode.DIRECT;
    }

    private boolean isComplexTask(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }

        String normalized = message.toLowerCase(Locale.ROOT);
        return message.length() >= 80
                || normalized.contains("计划")
                || normalized.contains("方案")
                || normalized.contains("步骤")
                || normalized.contains("分析")
                || normalized.contains("比较")
                || normalized.contains("调研")
                || normalized.contains("设计")
                || normalized.contains("实现")
                || normalized.contains("排查")
                || normalized.contains("优化")
                || normalized.contains("plan")
                || normalized.contains("analyze")
                || normalized.contains("compare")
                || normalized.contains("design")
                || normalized.contains("implement");
    }

    private String generatePlan(String message,
                                List<ChatMessage> history,
                                List<MemoryHit> memories,
                                ChatClient chatClient,
                                String selectedModel) {
        if (chatClient != null) {
            try {
                String plan = chatClient.prompt()
                        .system("""
                                Create a concise Plan-and-Solve plan for a desktop assistant.
                                Use Chinese. Return 3-5 numbered steps. Do not answer the task yet.
                                Prefer practical steps and mention tool use only when useful.
                                """)
                        .options(DashScopeChatOptions.builder()
                                .model(selectedModel)
                                .temperature(0.2)
                                .maxToken(600)
                                .build())
                        .user(buildPlanPrompt(message, history, memories))
                        .call()
                        .content();
                if (StringUtils.hasText(plan)) {
                    return plan.trim();
                }
            } catch (RuntimeException ignored) {
                return fallbackPlan(message);
            }
        }

        return fallbackPlan(message);
    }

    private String buildPlanPrompt(String message, List<ChatMessage> history, List<MemoryHit> memories) {
        StringBuilder prompt = new StringBuilder();
        if (history != null && !history.isEmpty()) {
            prompt.append("Recent conversation:\n");
            history.stream()
                    .skip(Math.max(0, history.size() - 6))
                    .forEach(item -> prompt.append("- ")
                            .append(item.role())
                            .append(": ")
                            .append(trimForPrompt(item.content(), 260))
                            .append('\n'));
            prompt.append('\n');
        }
        if (memories != null && !memories.isEmpty()) {
            prompt.append("Relevant long-term memories:\n")
                    .append(summarizeMemories(memories))
                    .append("\n\n");
        }
        prompt.append("User task:\n").append(message);
        return prompt.toString();
    }

    private String fallbackPlan(String message) {
        return """
                1. 明确用户问题的目标和限制。
                2. 结合当前会话摘要、最近消息和长期记忆补齐上下文。
                3. 如问题需要实时信息或工具能力，先使用可用工具获取证据。
                4. 汇总结果并给出可执行、可验证的回答。
                """.trim();
    }

    private String applyReflectionIfNeeded(String answer,
                                           String message,
                                           PreparedAgentContext agentContext,
                                           ChatClient chatClient,
                                           String selectedModel,
                                           boolean streaming,
                                           ChatStreamConsumer consumer) {
        if (!StringUtils.hasText(answer)
                || chatClient == null
                || agentContext == null
                || agentContext.runContext().run().getReflectionLimit() <= 0
                || agentContext.mode() == AgentMode.DIRECT) {
            return answer;
        }

        String currentAnswer = answer;
        for (int i = 0; i < agentContext.runContext().run().getReflectionLimit(); i++) {
            if (consumer != null) {
                emitUnchecked(consumer, ChatStreamEvent.status("reflecting", "反思中"));
            }
            String reflection;
            try {
                reflection = chatClient.prompt()
                        .system("""
                                You are a reflection checker for an assistant answer.
                                Check whether the answer missed critical context, ignored tool/search evidence, or contains likely contradictions.
                                Return exactly "OK" when no change is needed.
                                Otherwise return "IMPROVE: " followed by a concise Chinese supplement or correction.
                                """)
                        .options(DashScopeChatOptions.builder()
                                .model(selectedModel)
                                .temperature(0.1)
                                .maxToken(500)
                                .build())
                        .user(buildReflectionPrompt(message, currentAnswer, agentContext))
                        .call()
                        .content();
            } catch (RuntimeException ex) {
                addAgentStep(agentContext.runContext(), "reflection", "反思失败", ex.getMessage(), "failed", consumer);
                return currentAnswer;
            }

            if (!StringUtils.hasText(reflection)) {
                return currentAnswer;
            }

            String trimmed = reflection.trim();
            addAgentStep(agentContext.runContext(), "reflection", "Reflection 第 " + (i + 1) + " 轮", trimmed, "completed", consumer);
            if (isReflectionOk(trimmed)) {
                return currentAnswer;
            }

            String improvement = extractReflectionImprovement(trimmed);
            if (!StringUtils.hasText(improvement)) {
                return currentAnswer;
            }

            String revisedAnswer;
            try {
                revisedAnswer = reviseAnswerWithReflection(message, currentAnswer, improvement, agentContext, chatClient, selectedModel);
            } catch (RuntimeException ex) {
                addAgentStep(agentContext.runContext(), "reflection", "Reflection 修订失败", ex.getMessage(), "failed", consumer);
                return currentAnswer;
            }

            if (!StringUtils.hasText(revisedAnswer)) {
                return currentAnswer;
            }

            currentAnswer = revisedAnswer.trim();
            addAgentStep(
                    agentContext.runContext(),
                    "final",
                    "Reflection 修订回答",
                    "已根据 Reflection 建议重写最终回答，Reflection 内容仅保留在执行过程。",
                    "completed",
                    consumer
            );
            if (streaming && consumer != null) {
                emitUnchecked(consumer, ChatStreamEvent.status("answering", "回答中"));
                emitUnchecked(consumer, ChatStreamEvent.answerReset("已根据 Reflection 修订最终回答"));
                emitChunkedDeltaUnchecked(consumer, currentAnswer);
            }
        }

        return currentAnswer;
    }

    private String reviseAnswerWithReflection(String message,
                                              String currentAnswer,
                                              String improvement,
                                              PreparedAgentContext agentContext,
                                              ChatClient chatClient,
                                              String selectedModel) {
        String revised = chatClient.prompt()
                .system("""
                        You revise assistant answers after reflection.
                        Return only the final Chinese answer shown to the user.
                        Do not include reflection notes, review checklists, self-evaluation, labels like "补充修正", or process descriptions.
                        Keep useful content from the draft answer, integrate the requested correction, and make the final answer coherent.
                        """)
                .options(DashScopeChatOptions.builder()
                        .model(selectedModel)
                        .temperature(0.35)
                        .maxToken(maxTokens)
                        .build())
                .user(buildRevisionPrompt(message, currentAnswer, improvement, agentContext))
                .call()
                .content();
        return revised == null ? "" : revised.trim();
    }

    private String buildReflectionPrompt(String message, String answer, PreparedAgentContext agentContext) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("User question:\n")
                .append(message)
                .append("\n\nAssistant draft answer:\n")
                .append(answer)
                .append("\n\n");
        if (StringUtils.hasText(agentContext.plan())) {
            prompt.append("Plan:\n").append(agentContext.plan()).append("\n\n");
        }
        if (agentContext.weatherReport() != null && StringUtils.hasText(agentContext.weatherReport().context())) {
            prompt.append("Weather tool context:\n")
                    .append(agentContext.weatherReport().context())
                    .append("\n\n");
        }
        if (!agentContext.sources().isEmpty()) {
            prompt.append("Search sources:\n");
            for (SearchResult source : agentContext.sources()) {
                prompt.append("- ")
                        .append(source.title())
                        .append(" ")
                        .append(source.url())
                        .append('\n');
            }
            prompt.append('\n');
        }
        if (!agentContext.memories().isEmpty()) {
            prompt.append("Relevant memories:\n")
                    .append(summarizeMemories(agentContext.memories()))
                    .append('\n');
        }
        return prompt.toString();
    }

    private String buildRevisionPrompt(String message, String answer, String improvement, PreparedAgentContext agentContext) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("User question:\n")
                .append(message)
                .append("\n\nDraft answer:\n")
                .append(answer)
                .append("\n\nReflection improvement to integrate:\n")
                .append(improvement)
                .append("\n\n");
        if (StringUtils.hasText(agentContext.plan())) {
            prompt.append("Plan:\n").append(agentContext.plan()).append("\n\n");
        }
        if (agentContext.weatherReport() != null && StringUtils.hasText(agentContext.weatherReport().context())) {
            prompt.append("Weather tool context:\n")
                    .append(agentContext.weatherReport().context())
                    .append("\n\n");
        }
        if (!agentContext.sources().isEmpty()) {
            prompt.append("Search sources:\n");
            for (SearchResult source : agentContext.sources()) {
                prompt.append("- ")
                        .append(source.title())
                        .append(" ")
                        .append(source.url())
                        .append('\n');
            }
            prompt.append('\n');
        }
        if (!agentContext.memories().isEmpty()) {
            prompt.append("Relevant memories:\n")
                    .append(summarizeMemories(agentContext.memories()))
                    .append('\n');
        }
        prompt.append("""
                Rewrite the final answer now. The user should see only the answer, not the reflection process.
                """);
        return prompt.toString();
    }

    private boolean isReflectionOk(String reflection) {
        String normalized = reflection.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("ok")
                || normalized.startsWith("ok\n")
                || normalized.startsWith("无需")
                || normalized.contains("不需要修改");
    }

    private String extractReflectionImprovement(String reflection) {
        String trimmed = reflection.trim();
        if (trimmed.regionMatches(true, 0, "IMPROVE:", 0, "IMPROVE:".length())) {
            return trimmed.substring("IMPROVE:".length()).trim();
        }
        if (trimmed.regionMatches(true, 0, "REVISE:", 0, "REVISE:".length())) {
            return trimmed.substring("REVISE:".length()).trim();
        }
        return trimmed;
    }

    private AgentStepResponse addAgentStep(AgentRunContext runContext,
                                           String stepType,
                                           String title,
                                           String content,
                                           String status,
                                           ChatStreamConsumer consumer) {
        AgentStepResponse step = agentRunService.addStep(runContext, stepType, title, content, status);
        if (consumer != null) {
            emitUnchecked(consumer, ChatStreamEvent.agentStep(step));
        }
        return step;
    }

    private void emitUnchecked(ChatStreamConsumer consumer, ChatStreamEvent event) {
        try {
            emit(consumer, event);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private String summarizeMemories(List<MemoryHit> memories) {
        if (memories == null || memories.isEmpty()) {
            return "";
        }

        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < memories.size(); i++) {
            MemoryHit memory = memories.get(i);
            summary.append(i + 1)
                    .append(". ")
                    .append(trimForPrompt(memory.content(), 360))
                    .append("（相关度 ")
                    .append(String.format(Locale.ROOT, "%.2f", memory.score()))
                    .append("）\n");
        }
        return summary.toString().trim();
    }

    private List<SearchResult> deduplicateSources(List<SearchResult> sources) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }

        Map<String, SearchResult> deduplicated = new java.util.LinkedHashMap<>();
        for (SearchResult source : sources) {
            if (source == null || !StringUtils.hasText(source.url())) {
                continue;
            }
            deduplicated.putIfAbsent(source.url(), source);
        }
        return new ArrayList<>(deduplicated.values());
    }

    private void persistPartialIfNeeded(AgentRunContext runContext,
                                        StringBuilder streamedAnswer,
                                        boolean realtimeSearchUsed,
                                        boolean modelAvailable,
                                        List<SearchResult> sources,
                                        int[] lastSavedLength,
                                        long[] lastSavedAt) {
        if (runContext == null || streamedAnswer == null || streamedAnswer.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        int length = streamedAnswer.length();
        boolean enoughText = length - lastSavedLength[0] >= PARTIAL_SAVE_CHAR_INTERVAL;
        boolean enoughTime = now - lastSavedAt[0] >= PARTIAL_SAVE_MILLIS;
        if (!enoughText && !enoughTime) {
            return;
        }

        agentRunService.updateProgress(runContext, streamedAnswer.toString(), realtimeSearchUsed, modelAvailable, sources);
        lastSavedLength[0] = length;
        lastSavedAt[0] = now;
    }

    private void saveCancelledPartial(ConversationEntity conversation,
                                      String partialAnswer,
                                      String model,
                                      boolean realtimeSearchUsed,
                                      boolean modelAvailable,
                                      List<SearchResult> sources,
                                      AgentRunContext runContext,
                                      String userMessage) {
        if (agentRunService.isCancelled(runContext.run().getId())) {
            return;
        }
        String content = StringUtils.hasText(partialAnswer) ? partialAnswer : CANCELLED_ANSWER;
        if (!agentRunService.cancelRun(runContext.run(), content)) {
            return;
        }
        MessageEntity savedMessage = conversationService.saveAssistantMessage(
                conversation,
                content,
                model,
                realtimeSearchUsed,
                modelAvailable,
                sources,
                "cancelled",
                messageBlockFactory.assistantBlocks(content, runContext, sources, "cancelled")
        );
        agentRunService.attachAssistantMessage(runContext, savedMessage);
    }

    private UUID parseUuid(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("缺少 UUID。");
        }
        return UUID.fromString(value.trim());
    }

    private static class AgentRunCancelledException extends RuntimeException {
    }

    private String extractStreamContent(org.springframework.ai.chat.model.ChatResponse response) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return "";
        }

        List<String> parts = new ArrayList<>();
        for (var generation : response.getResults()) {
            if (generation == null || generation.getOutput() == null) {
                continue;
            }

            String text = generation.getOutput().getText();
            if (StringUtils.hasText(text)) {
                parts.add(text);
            }
        }

        return String.join("", parts);
    }

    private void emit(ChatStreamConsumer consumer, ChatStreamEvent event) throws IOException {
        consumer.accept(event);
    }

    private void emitChunkedDelta(ChatStreamConsumer consumer, String content) throws IOException {
        if (consumer == null || !StringUtils.hasText(content)) {
            return;
        }

        int chunkSize = 12;
        for (int index = 0; index < content.length(); index += chunkSize) {
            emit(consumer, ChatStreamEvent.delta(content.substring(index, Math.min(content.length(), index + chunkSize))));
        }
    }

    private String beginMarkdownBlock(ChatStreamConsumer consumer) throws IOException {
        String blockId = "markdown-" + UUID.randomUUID();
        emit(consumer, ChatStreamEvent.blockStart(MessageBlock.markdown(blockId, "", true)));
        return blockId;
    }

    private void emitAnswerDelta(ChatStreamConsumer consumer, String blockId, String content) throws IOException {
        if (consumer == null || !StringUtils.hasText(content)) {
            return;
        }
        if (StringUtils.hasText(blockId)) {
            emit(consumer, ChatStreamEvent.blockDelta(blockId, content));
        }
        emit(consumer, ChatStreamEvent.delta(content));
    }

    private void emitChunkedAnswerDelta(ChatStreamConsumer consumer, String blockId, String content) throws IOException {
        if (consumer == null || !StringUtils.hasText(content)) {
            return;
        }

        int chunkSize = 12;
        for (int index = 0; index < content.length(); index += chunkSize) {
            emitAnswerDelta(consumer, blockId, content.substring(index, Math.min(content.length(), index + chunkSize)));
        }
    }

    private void endMarkdownBlock(ChatStreamConsumer consumer, String blockId) throws IOException {
        if (consumer != null && StringUtils.hasText(blockId)) {
            emit(consumer, ChatStreamEvent.blockEnd(blockId));
        }
    }

    private void emitChunkedDeltaUnchecked(ChatStreamConsumer consumer, String content) {
        try {
            emitChunkedDelta(consumer, content);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private void emitSourcesMeta(AgentRunContext runContext,
                                 boolean realtimeSearchUsed,
                                 boolean modelAvailable,
                                 List<SearchResult> sources,
                                 ChatStreamConsumer consumer) throws IOException {
        if (runContext == null || consumer == null || sources == null || sources.isEmpty()) {
            return;
        }

        List<SearchResult> deduplicatedSources = deduplicateSources(sources);
        agentRunService.updateProgress(runContext, null, realtimeSearchUsed, modelAvailable, deduplicatedSources);
        emit(consumer, ChatStreamEvent.meta(
                runContext.run().getModel(),
                realtimeSearchUsed,
                modelAvailable,
                deduplicatedSources,
                runContext.run().getConversation().getId(),
                null,
                runContext.run().getUserMessageId(),
                runContext.run().getId()
        ));
    }

    private boolean runModelAvailable(AgentRunContext runContext) {
        return runContext == null
                || runContext.run().getModelAvailable() == null
                || runContext.run().getModelAvailable();
    }

    private ChatResponse persistChatResponse(ConversationEntity conversation,
                                             String answer,
                                             boolean realtimeSearchUsed,
                                             boolean modelAvailable,
                                             String model,
                                             List<SearchResult> sources,
                                             AgentRunContext runContext,
                                             String userMessage) {
        MessageEntity savedMessage = saveAssistantMessageAndFinalize(
                conversation,
                answer,
                model,
                realtimeSearchUsed,
                modelAvailable,
                sources,
                runContext,
                userMessage
        );
        String savedAnswer = savedMessage.getContent();
        return new ChatResponse(
                savedAnswer,
                conversationService.readMessageBlocks(savedMessage, runContext, sources),
                realtimeSearchUsed,
                modelAvailable,
                model,
                sources,
                conversation.getId(),
                savedMessage.getId(),
                runContext == null ? null : runContext.run().getId(),
                runContext == null ? List.of() : runContext.steps()
        );
    }

    private MessageEntity saveAssistantMessageAndFinalize(ConversationEntity conversation,
                                                          String answer,
                                                          String model,
                                                          boolean realtimeSearchUsed,
                                                          boolean modelAvailable,
                                                          List<SearchResult> sources,
                                                          AgentRunContext runContext,
                                                          String userMessage) {
        String finalAnswer = normalizeFinalAnswer(answer, sources);
        MessageEntity savedMessage = conversationService.saveAssistantMessage(
                conversation,
                finalAnswer,
                model,
                realtimeSearchUsed,
                modelAvailable,
                sources,
                "completed",
                messageBlockFactory.assistantBlocks(finalAnswer, runContext, sources, "completed")
        );
        refreshConversationSummaryIfNeeded(conversation, model);
        memoryService.rememberConversationTurn(
                conversation.getId(),
                savedMessage.getId(),
                userMessage,
                finalAnswer,
                model,
                sources == null ? 0 : sources.size()
        );
        pendingAgentActionService.registerFromAssistantTurn(conversation, savedMessage, userMessage, finalAnswer);
        if (runContext != null) {
            agentRunService.completeRun(runContext, savedMessage, finalAnswer);
        }
        return savedMessage;
    }

    private String normalizeFinalAnswer(String answer, List<SearchResult> sources) {
        if (StringUtils.hasText(answer)) {
            return answer;
        }
        if (sources != null && !sources.isEmpty()) {
            return "没有收到模型正文，以下是本次检索到的参考信息。\n\n" + fallbackSearchSummary(sources);
        }

        return "没有收到模型返回内容。";
    }

    private void refreshConversationSummaryIfNeeded(ConversationEntity conversation, String selectedModel) {
        if (!conversationService.shouldRefreshSummary(conversation.getId(), SUMMARY_MIN_MESSAGES, SUMMARY_REFRESH_INTERVAL)) {
            return;
        }

        String previousSummary = conversationService.summaryText(conversation.getId()).orElse("");
        List<ChatMessage> sourceMessages = conversationService.messagesForSummary(conversation.getId(), SUMMARY_SOURCE_LIMIT);
        String summary = generateConversationSummary(previousSummary, sourceMessages, selectedModel);
        conversationService.upsertSummary(conversation, summary);
    }

    private String generateConversationSummary(String previousSummary, List<ChatMessage> messages, String selectedModel) {
        if (isModelConfigured()) {
            ChatClient chatClient = chatClientProvider.getIfAvailable();
            if (chatClient != null) {
                try {
                    String summary = chatClient.prompt()
                            .system("""
                                    Summarize a desktop assistant conversation for future context.
                                    Keep durable facts, user preferences, open tasks, chosen files/locations/models, decisions, and unresolved issues.
                                    Do not preserve incorrect claims about the assistant's underlying model identity.
                                    Write in concise Chinese bullet points. Do not invent facts.
                                    """)
                            .options(DashScopeChatOptions.builder()
                                    .model(selectedModel)
                                    .temperature(0.2)
                                    .maxToken(700)
                                    .build())
                            .user(buildSummaryPrompt(previousSummary, messages))
                            .call()
                            .content();
                    if (StringUtils.hasText(summary)) {
                        return summary.trim();
                    }
                } catch (RuntimeException ignored) {
                    return fallbackConversationSummary(messages);
                }
            }
        }

        return fallbackConversationSummary(messages);
    }

    private String buildSummaryPrompt(String previousSummary, List<ChatMessage> messages) {
        StringBuilder prompt = new StringBuilder();
        if (StringUtils.hasText(previousSummary)) {
            prompt.append("Existing summary:\n")
                    .append(previousSummary)
                    .append("\n\n");
        }

        prompt.append("Recent messages to merge:\n");
        for (ChatMessage message : messages) {
            if (message == null || !StringUtils.hasText(message.content())) {
                continue;
            }
            prompt.append("- ")
                    .append(StringUtils.hasText(message.role()) ? message.role() : "unknown")
                    .append(": ")
                    .append(trimForPrompt(message.content(), 900))
                    .append('\n');
        }

        return prompt.toString();
    }

    private String fallbackConversationSummary(List<ChatMessage> messages) {
        StringBuilder summary = new StringBuilder("最近会话要点：");
        messages.stream()
                .filter(message -> message != null && StringUtils.hasText(message.content()))
                .skip(Math.max(0, messages.size() - 8))
                .forEach(message -> summary
                        .append('\n')
                        .append("- ")
                        .append(StringUtils.hasText(message.role()) ? message.role() : "unknown")
                        .append(": ")
                        .append(trimForPrompt(message.content(), 180)));
        return summary.toString();
    }

    private String trimForPrompt(String content, int maxLength) {
        if (!StringUtils.hasText(content) || content.length() <= maxLength) {
            return content == null ? "" : content;
        }

        return content.substring(0, Math.max(0, maxLength - 1)) + "…";
    }

    @FunctionalInterface
    public interface ChatStreamConsumer {
        void accept(ChatStreamEvent event) throws IOException;
    }

    private String resolveModel(ChatRequest request, String message) {
        if (request != null && StringUtils.hasText(request.model())) {
            return request.model().trim().toLowerCase(Locale.ROOT);
        }

        Matcher matcher = LEADING_MODEL_COMMAND.matcher(message == null ? "" : message);
        if (matcher.find()) {
            return matcher.group(1).trim().toLowerCase(Locale.ROOT);
        }

        return DEFAULT_MODEL;
    }

    private String removeLeadingModelCommand(String message) {
        if (!StringUtils.hasText(message)) {
            return "";
        }

        Matcher matcher = LEADING_MODEL_COMMAND.matcher(message);
        if (!matcher.find()) {
            return message;
        }

        return message.substring(matcher.end()).trim();
    }

    private boolean shouldAnswerModelIdentity(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }

        String compact = message.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "")
                .replaceAll("[，。！？?、,.]", "");
        String lower = message.toLowerCase(Locale.ROOT);

        boolean chineseModelQuestion = compact.contains("模型")
                && (compact.contains("底层")
                || compact.contains("当前")
                || compact.contains("是什么")
                || compact.contains("哪个")
                || compact.contains("哪一个")
                || compact.contains("使用")
                || compact.contains("用的")
                || compact.contains("你是")
                || compact.contains("你用"));
        boolean chineseVendorQuestion = compact.contains("你")
                && (compact.contains("gpt")
                || compact.contains("claude")
                || compact.contains("gemini")
                || compact.contains("deepseek")
                || compact.contains("通义")
                || compact.contains("千问"));
        boolean englishQuestion = lower.contains("what model")
                || lower.contains("which model")
                || lower.contains("current model")
                || lower.contains("underlying model")
                || lower.contains("base model")
                || lower.contains("are you gpt")
                || lower.contains("are you claude")
                || lower.contains("are you gemini")
                || lower.contains("are you deepseek");

        return chineseModelQuestion || chineseVendorQuestion || englishQuestion;
    }

    private String modelIdentityAnswer(String selectedModel) {
        String modelName = modelDisplayName(selectedModel);
        String availability = isModelConfigured()
                ? "当前后端已配置 DashScope API Key，可以调用该模型。"
                : "当前后端还没有配置 DASHSCOPE_API_KEY，因此暂时不能真正调用该模型。";

        return """
                当前会话使用的底层模型是 %s，模型 ID 为 `%s`。
                调用链路是 %s。

                我是 Universal Assistant 桌面助手，不是 Claude、GPT 或 Gemini；如果后续切换模型，我会按实际选中的模型 ID 回答。
                %s
                """.formatted(modelName, selectedModel, MODEL_INTEGRATION, availability).trim();
    }

    private String modelDisplayName(String selectedModel) {
        return SUPPORTED_MODELS.getOrDefault(selectedModel, selectedModel);
    }

    private boolean shouldUseSearch(String message, Boolean requested) {
        if (Boolean.TRUE.equals(requested)) {
            return true;
        }

        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("最新")
                || normalized.contains("今天")
                || normalized.contains("现在")
                || normalized.contains("实时")
                || normalized.contains("新闻")
                || normalized.contains("搜索")
                || normalized.contains("查询")
                || normalized.contains("天气")
                || normalized.contains("气温")
                || normalized.contains("温度")
                || normalized.contains("下雨")
                || normalized.contains("降雨")
                || normalized.contains("预报")
                || normalized.contains("价格")
                || normalized.contains("latest")
                || normalized.contains("today")
                || normalized.contains("current")
                || normalized.contains("weather")
                || normalized.contains("forecast")
                || normalized.contains("temperature")
                || normalized.contains("news");
    }

    private boolean shouldUseWeatherTool(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }

        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("天气")
                || normalized.contains("气温")
                || normalized.contains("温度")
                || normalized.contains("下雨")
                || normalized.contains("降雨")
                || normalized.contains("降水")
                || normalized.contains("预报")
                || normalized.contains("weather")
                || normalized.contains("forecast")
                || normalized.contains("temperature");
    }

    private WeatherReport aggregateWeatherReports(WeatherPlan weatherPlan, List<WeatherQueryResult> results) {
        if (results == null || results.isEmpty()) {
            return WeatherReport.unavailable("", "没有生成可执行的天气查询。", List.of());
        }

        boolean anyAvailable = results.stream().anyMatch(result -> result.report() != null && result.report().available());
        StringBuilder summary = new StringBuilder("天气查询结果：");
        StringBuilder context = new StringBuilder("Weather query plan:\n")
                .append(weatherPlan.stepSummary())
                .append("\n\nWeather tool results:\n");
        List<SearchResult> reportSources = new ArrayList<>();

        for (WeatherQueryResult result : results) {
            WeatherQuery query = result.query();
            WeatherReport report = result.report();
            String reportSummary = report == null ? "天气工具没有返回结果。" : compactLine(report.summary());
            summary.append('\n')
                    .append("- ")
                    .append(query.displayText())
                    .append("：")
                    .append(reportSummary);
            context.append("\nQuery: ")
                    .append(query.callText())
                    .append("\nStatus: ")
                    .append(result.status())
                    .append("\nSource: ")
                    .append(query.source())
                    .append("\n")
                    .append(report == null ? "No weather result." : report.context())
                    .append('\n');
            if (report != null && report.sources() != null) {
                reportSources.addAll(report.sources());
            }
        }

        return new WeatherReport(
                anyAvailable,
                weatherPlan.queries().stream().map(WeatherQuery::resolvedLocation).distinct().reduce((left, right) -> left + "、" + right).orElse(""),
                summary.toString(),
                context.toString(),
                deduplicateSources(reportSources)
        );
    }

    private String weatherToolStatus(WeatherReport weatherReport) {
        if (weatherReport == null) {
            return "unavailable";
        }
        String text = ((weatherReport.summary() == null ? "" : weatherReport.summary())
                + "\n"
                + (weatherReport.context() == null ? "" : weatherReport.context())).toLowerCase(Locale.ROOT);
        if (text.contains("已中断") || text.contains("interrupted")) {
            return "cancelled";
        }
        return weatherReport.available() ? "completed" : "unavailable";
    }

    private boolean shouldSearchWeatherFallback(boolean weatherIntent, WeatherPlan weatherPlan, WeatherReport weatherReport) {
        return weatherIntent
                && weatherPlan != null
                && !weatherPlan.needsClarification()
                && !weatherPlan.queries().isEmpty()
                && weatherReport != null
                && (!weatherReport.available() || hasUnavailableWeatherResult(weatherReport));
    }

    private boolean hasUnavailableWeatherResult(WeatherReport weatherReport) {
        return weatherReport != null
                && StringUtils.hasText(weatherReport.context())
                && weatherReport.context().contains("Status: unavailable");
    }

    private String weatherFallbackQuery(WeatherPlan weatherPlan, String fallbackMessage) {
        if (weatherPlan == null || weatherPlan.queries().isEmpty()) {
            return fallbackMessage;
        }
        String locations = weatherPlan.queries().stream()
                .map(WeatherQuery::resolvedLocation)
                .distinct()
                .reduce((left, right) -> left + " " + right)
                .orElse("");
        String dates = weatherPlan.queries().stream()
                .map(WeatherQuery::targetDate)
                .distinct()
                .reduce((left, right) -> left + " " + right)
                .orElse("");
        return (locations + " " + dates + " 天气 预报").trim();
    }

    private String completeIncompleteAnswerIfNeeded(String answer,
                                                    String message,
                                                    PreparedAgentContext agentContext,
                                                    boolean streaming,
                                                    ChatStreamConsumer consumer) {
        if (!looksIncompleteWeatherAnswer(answer, agentContext)) {
            return answer;
        }

        String supplement = buildWeatherCompletionSupplement(message, agentContext);
        if (!StringUtils.hasText(supplement)) {
            return answer;
        }

        String finalAnswer = (StringUtils.hasText(answer) ? answer.trim() + "\n\n" : "") + supplement;
        addAgentStep(
                agentContext.runContext(),
                "final",
                "回答兜底补全",
                "模型返回内容疑似停留在开场白，已基于天气工具结果补全最终回答。",
                "completed",
                consumer
        );
        if (streaming && consumer != null) {
            emitUnchecked(consumer, ChatStreamEvent.status("answering", "回答中"));
            emitChunkedDeltaUnchecked(consumer, "\n\n" + supplement);
        }
        return finalAnswer;
    }

    private boolean looksIncompleteWeatherAnswer(String answer, PreparedAgentContext agentContext) {
        if (agentContext == null || agentContext.weatherReport() == null || !StringUtils.hasText(agentContext.weatherReport().summary())) {
            return false;
        }
        String trimmed = answer == null ? "" : answer.trim();
        if (!StringUtils.hasText(trimmed)) {
            return true;
        }
        boolean openerOnly = trimmed.length() < 120
                && (trimmed.contains("我帮你查")
                || trimmed.contains("我来查")
                || trimmed.contains("帮你查询")
                || trimmed.contains("好的")
                || trimmed.contains("我会帮你"));
        boolean ignoredWeatherEvidence = agentContext.weatherReport().summary().contains("°C")
                && !trimmed.contains("°C")
                && trimmed.length() < 180;
        return openerOnly || ignoredWeatherEvidence;
    }

    private String buildWeatherCompletionSupplement(String message, PreparedAgentContext agentContext) {
        WeatherReport weatherReport = agentContext.weatherReport();
        if (weatherReport == null || !StringUtils.hasText(weatherReport.summary())) {
            return "";
        }

        StringBuilder supplement = new StringBuilder("基于已完成的天气工具结果，补充如下：\n\n")
                .append(weatherReport.summary());
        if (isTravelWeatherQuestion(message)) {
            supplement.append("\n\n行程细化建议：")
                    .append("\n- 优先把室内或遮蔽性较好的点安排在高温、降雨或午后时段。")
                    .append("\n- 户外拍照、步行和夜游项目建议避开明显降雨时段，并预留交通缓冲。")
                    .append("\n- 如果多个景点所在区域天气接近，可以按原路线推进；如果某一区域降雨更明显，优先把该区域改为室内备选。");
        }
        return supplement.toString();
    }

    private boolean isTravelWeatherQuestion(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        return message.contains("景点")
                || message.contains("行程")
                || message.contains("路线")
                || message.contains("旅游")
                || message.contains("游玩")
                || message.contains("安排")
                || message.contains("细化");
    }

    private String compactLine(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private List<SearchResult> searchSafely(String query, int limit) {
        try {
            return searchService.search(query, limit);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private String extractWeatherLocation(String message) {
        if (!StringUtils.hasText(message)) {
            return "";
        }

        return message
                .replaceAll("(?i)weather|forecast|temperature", " ")
                .replaceAll("\\d{4}-\\d{2}-\\d{2}|\\d{1,2}\\s*月\\s*\\d{1,2}\\s*(?:日|号)?|(?<!\\d)\\d{1,2}[/-]\\d{1,2}(?!\\d)", " ")
                .replaceAll("(?i)today|tomorrow|yesterday|current|now", " ")
                .replaceAll("今天|今日|明天|后天|昨天|前天|现在|当前|实时|天气|气温|温度|下雨|降雨|降水|预报|怎么样|如何|查询|查一下|帮我|请问|请|的|吗|呢", " ")
                .replaceAll("[，。！？?、,.]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String extractWeatherDate(String message) {
        if (!StringUtils.hasText(message)) {
            return "today";
        }

        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("后天")) {
            return "后天";
        }
        if (normalized.contains("明天") || normalized.contains("tomorrow")) {
            return "明天";
        }
        if (normalized.contains("前天")) {
            return "前天";
        }
        if (normalized.contains("昨天") || normalized.contains("yesterday")) {
            return "昨天";
        }
        if (normalized.contains("今天")
                || normalized.contains("今日")
                || normalized.contains("现在")
                || normalized.contains("当前")
                || normalized.contains("today")
                || normalized.contains("current")
                || normalized.contains("now")) {
            return "今天";
        }

        Matcher isoMatcher = ISO_DATE_PATTERN.matcher(normalized);
        if (isoMatcher.find()) {
            return isoMatcher.group();
        }

        Matcher chineseMatcher = CHINESE_MONTH_DAY_PATTERN.matcher(normalized);
        if (chineseMatcher.find()) {
            return chineseMatcher.group();
        }

        Matcher shortMatcher = SHORT_MONTH_DAY_PATTERN.matcher(normalized);
        if (shortMatcher.find()) {
            return shortMatcher.group();
        }

        return "today";
    }

    private boolean isModelConfigured() {
        return StringUtils.hasText(dashScopeApiKey) && !"missing-api-key".equals(dashScopeApiKey);
    }

    private String buildUserPrompt(String message,
                                   String conversationSummary,
                                   List<ChatMessage> history,
                                   List<SearchResult> sources,
                                   WeatherReport weatherReport,
                                   List<MemoryHit> memories,
                                   String plan,
                                   String selectedModel) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("Runtime model metadata:\n")
                .append("Provider: ")
                .append(MODEL_PROVIDER)
                .append('\n')
                .append("Model ID: ")
                .append(selectedModel)
                .append('\n')
                .append("Model display name: ")
                .append(modelDisplayName(selectedModel))
                .append('\n')
                .append("Integration: ")
                .append(MODEL_INTEGRATION)
                .append('\n')
                .append("If the user asks what model you are using, answer based only on this runtime model metadata. ")
                .append("Do not repeat conflicting model identity claims from conversation history.\n\n");

        if (StringUtils.hasText(conversationSummary)) {
            prompt.append("Conversation summary:\n")
                    .append(conversationSummary)
                    .append("\n\n");
        }

        if (history != null && !history.isEmpty()) {
            prompt.append("Recent conversation:\n");
            int fromIndex = Math.max(0, history.size() - 8);
            history.stream()
                    .skip(fromIndex)
                    .filter(item -> item != null && StringUtils.hasText(item.content()))
                    .forEach(item -> prompt
                            .append("- ")
                            .append(StringUtils.hasText(item.role()) ? item.role() : "unknown")
                            .append(": ")
                            .append(item.content())
                            .append('\n'));
            prompt.append('\n');
        }

        if (memories != null && !memories.isEmpty()) {
            prompt.append("Relevant long-term memories:\n")
                    .append(summarizeMemories(memories))
                    .append("\n\n");
        }

        if (StringUtils.hasText(plan)) {
            prompt.append("Plan-and-Solve plan:\n")
                    .append(plan)
                    .append("\nUse this plan to structure the work, but do not mechanically expose internal process unless it helps the user.\n\n");
        }

        if (!sources.isEmpty()) {
            prompt.append("Search context:\n");
            for (int i = 0; i < sources.size(); i++) {
                SearchResult result = sources.get(i);
                prompt.append(i + 1)
                        .append(". ")
                        .append(result.title())
                        .append('\n')
                        .append("URL: ")
                        .append(result.url())
                        .append('\n')
                        .append("Snippet: ")
                        .append(result.snippet())
                        .append("\n\n");
            }
        }

        if (weatherReport != null && StringUtils.hasText(weatherReport.context())) {
            prompt.append("Tool context:\n")
                    .append(weatherReport.context())
                    .append('\n');
            prompt.append("If the weather context contains multiple query results, cover every location/date result instead of only saying you will check it.\n");
            if (!weatherReport.available() && !sources.isEmpty()) {
                prompt.append("Weather tool status: unavailable. Use the search context as fallback if it contains relevant weather information, and explain that the direct weather service was unavailable.\n\n");
            }
        }

        prompt.append("User question:\n").append(message);
        return prompt.toString();
    }

    private String fallbackAnswerWithoutModel(List<SearchResult> sources) {
        if (sources.isEmpty()) {
            return "后端已启动，但还没有配置 DASHSCOPE_API_KEY，暂时无法调用大模型。设置环境变量后重启后端即可使用聊天能力。";
        }

        return "后端还没有配置 DASHSCOPE_API_KEY，暂时无法调用大模型生成综合回答。\n\n" + fallbackSearchSummary(sources);
    }

    private String fallbackAnswer(WeatherReport weatherReport, List<SearchResult> sources, boolean modelAvailable) {
        if (weatherReport != null && StringUtils.hasText(weatherReport.summary())) {
            if (weatherReport.available()) {
                return weatherReport.summary();
            }
            if (!sources.isEmpty()) {
                return weatherReport.summary() + "\n\n" + fallbackSearchSummary(sources);
            }

            return weatherReport.summary();
        }

        if (modelAvailable) {
            if (sources.isEmpty()) {
                return "模型没有返回有效内容。";
            }

            return "模型没有返回有效内容，以下是可用参考信息。\n\n" + fallbackSearchSummary(sources);
        }

        return fallbackAnswerWithoutModel(sources);
    }

    private String fallbackSearchSummary(List<SearchResult> sources) {
        StringBuilder summary = new StringBuilder("临时检索结果：");
        for (int i = 0; i < sources.size(); i++) {
            SearchResult result = sources.get(i);
            summary.append('\n')
                    .append(i + 1)
                    .append(". ")
                    .append(result.title())
                    .append('\n')
                    .append(result.url());
            if (StringUtils.hasText(result.snippet())) {
                summary.append('\n').append(result.snippet());
            }
        }
        return summary.toString();
    }
}
