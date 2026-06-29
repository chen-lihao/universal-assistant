package com.hao.universalassistantbackend.service;

import com.hao.universalassistantbackend.entity.ConversationEntity;
import com.hao.universalassistantbackend.entity.MessageEntity;
import com.hao.universalassistantbackend.model.AgentMode;
import com.hao.universalassistantbackend.model.AgentRunContext;
import com.hao.universalassistantbackend.model.AgentStepResponse;
import com.hao.universalassistantbackend.model.ChatMessage;
import com.hao.universalassistantbackend.model.ChatRequest;
import com.hao.universalassistantbackend.model.ChatResponse;
import com.hao.universalassistantbackend.model.ChatStreamEvent;
import com.hao.universalassistantbackend.model.MemoryHit;
import com.hao.universalassistantbackend.model.SearchResult;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final Pattern LEADING_MODEL_COMMAND = Pattern.compile(
            "^\\s*(?:/model\\s+|@)([a-zA-Z0-9_.-]+)(?:\\s+|$)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern CHINESE_MONTH_DAY_PATTERN = Pattern.compile("\\d{1,2}\\s*月\\s*\\d{1,2}\\s*(?:日|号)?");
    private static final Pattern SHORT_MONTH_DAY_PATTERN = Pattern.compile("(?<!\\d)\\d{1,2}[/-]\\d{1,2}(?!\\d)");

    private static final String SYSTEM_PROMPT = """
            You are Universal Assistant, a desktop AI assistant.
            Answer in Chinese by default unless the user asks for another language.
            Be concise, practical, and honest about uncertainty.
            When search context is provided, use it and cite source links naturally.
            When weather tool context is provided, use it as the primary weather source and cite the source links naturally.
            For local file operations, explain the intended operation and remind the user that the desktop app must confirm writes.
            You must not guess your underlying model. If asked about the current model, use only the runtime model metadata provided by the backend.
            Never claim to be Claude, GPT, Gemini, or another model unless the runtime model metadata explicitly says so.
            """;

    private final ObjectProvider<ChatClient> chatClientProvider;
    private final SearchService searchService;
    private final ConversationService conversationService;
    private final AgentRunService agentRunService;
    private final MemoryService memoryService;
    private final WeatherTools weatherTools;
    private final String dashScopeApiKey;
    private final int maxTokens;

    public ChatService(@Qualifier("deepSeekChatClient") ObjectProvider<ChatClient> chatClientProvider,
                       SearchService searchService,
                       ConversationService conversationService,
                       AgentRunService agentRunService,
                       MemoryService memoryService,
                       WeatherTools weatherTools,
                       @Value("${spring.ai.dashscope.api-key:}") String dashScopeApiKey,
                       @Value("${assistant.chat.max-tokens:2400}") int maxTokens) {
        this.chatClientProvider = chatClientProvider;
        this.searchService = searchService;
        this.conversationService = conversationService;
        this.agentRunService = agentRunService;
        this.memoryService = memoryService;
        this.weatherTools = weatherTools;
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
        String conversationSummary = conversationService.summaryText(conversation.getId()).orElse("");
        List<ChatMessage> history = conversationService.recentHistory(conversation.getId(), 16);
        if (history.isEmpty() && request != null && request.history() != null) {
            history = request.history();
        }
        MessageEntity userMessage = conversationService.saveUserMessage(conversation, message);
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
        String conversationSummary = conversationService.summaryText(conversation.getId()).orElse("");
        List<ChatMessage> history = conversationService.recentHistory(conversation.getId(), 16);
        if (history.isEmpty() && request != null && request.history() != null) {
            history = request.history();
        }
        MessageEntity userMessage = conversationService.saveUserMessage(conversation, message);
        if (shouldAnswerModelIdentity(message)) {
            boolean modelAvailable = isModelConfigured();
            AgentRunContext runContext = agentRunService.startRun(conversation, userMessage, AgentMode.DIRECT, message, selectedModel);
            emit(consumer, ChatStreamEvent.meta(selectedModel, false, modelAvailable, List.of(), conversation.getId(), null, runContext.run().getId()));
            addAgentStep(runContext, "thought", "识别模型身份问题", "用户询问当前底层模型，直接使用后端运行时模型元数据回答。", "completed", consumer);
            String answer = modelIdentityAnswer(selectedModel);
            MessageEntity savedMessage = saveAssistantMessageAndFinalize(conversation, answer, selectedModel, false, modelAvailable, List.of(), runContext, message);
            emit(consumer, ChatStreamEvent.meta(selectedModel, false, modelAvailable, List.of(), conversation.getId(), savedMessage.getId(), runContext.run().getId()));
            emit(consumer, ChatStreamEvent.status("answering", "回答中"));
            emit(consumer, ChatStreamEvent.delta(answer));
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
                agentContext.runContext().run().getId()
        ));
        emit(consumer, ChatStreamEvent.status("answering", "回答中"));

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
            emit(consumer, ChatStreamEvent.meta(selectedModel, true, modelAvailable, agentContext.sources(), conversation.getId(), savedMessage.getId(), agentContext.runContext().run().getId()));
            emit(consumer, ChatStreamEvent.delta(agentContext.weatherReport().summary()));
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
            emit(consumer, ChatStreamEvent.meta(selectedModel, agentContext.useSearch(), false, agentContext.sources(), conversation.getId(), savedMessage.getId(), agentContext.runContext().run().getId()));
            emit(consumer, ChatStreamEvent.delta(answer));
            emit(consumer, ChatStreamEvent.done());
            return;
        }

        try {
            StringBuilder streamedAnswer = new StringBuilder();
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
                        streamedAnswer.append(content);
                        try {
                            consumer.accept(ChatStreamEvent.delta(content));
                        } catch (IOException ex) {
                            throw new UncheckedIOException(ex);
                        }
                    })
                    .blockLast();

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
                    emit(consumer, ChatStreamEvent.meta(selectedModel, agentContext.useSearch(), true, agentContext.sources(), conversation.getId(), savedMessage.getId(), agentContext.runContext().run().getId()));
                    emit(consumer, ChatStreamEvent.delta(fallbackAnswer));
                } else {
                    emit(consumer, ChatStreamEvent.error("没有收到模型返回内容。"));
                }
            } else {
                String finalAnswer = applyReflectionIfNeeded(
                        streamedAnswer.toString(),
                        message,
                        agentContext,
                        chatClient,
                        selectedModel,
                        true,
                        consumer
                );
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
                emit(consumer, ChatStreamEvent.meta(selectedModel, agentContext.useSearch(), true, agentContext.sources(), conversation.getId(), savedMessage.getId(), agentContext.runContext().run().getId()));
            }

            emit(consumer, ChatStreamEvent.done());
        } catch (UncheckedIOException ex) {
            throw ex.getCause();
        } catch (RuntimeException ex) {
            String fallback = fallbackAnswer(agentContext.weatherReport(), agentContext.sources(), true);
            String answer = StringUtils.hasText(fallback)
                    ? "模型调用失败：" + ex.getMessage() + "\n\n" + fallback
                    : "模型调用失败：" + ex.getMessage();
            if (StringUtils.hasText(answer)) {
                addAgentStep(agentContext.runContext(), "error", "模型调用失败", ex.getMessage(), "failed", consumer);
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
                emit(consumer, ChatStreamEvent.meta(selectedModel, agentContext.useSearch(), false, agentContext.sources(), conversation.getId(), savedMessage.getId(), agentContext.runContext().run().getId()));
                emit(consumer, ChatStreamEvent.delta(answer));
            } else {
                emit(consumer, ChatStreamEvent.error("模型调用失败：" + ex.getMessage()));
                if (!agentContext.sources().isEmpty()) {
                    emit(consumer, ChatStreamEvent.delta("\n\n" + fallbackSearchSummary(agentContext.sources())));
                }
            }
            emit(consumer, ChatStreamEvent.done());
        }
    }

    private PreparedAgentContext prepareAgentContext(ConversationEntity conversation,
                                                     MessageEntity userMessage,
                                                     String message,
                                                     Boolean requestedSearch,
                                                     String selectedModel,
                                                     List<ChatMessage> history,
                                                     ChatClient chatClient,
                                                     boolean modelAvailable,
                                                     ChatStreamConsumer consumer) throws IOException {
        boolean weatherIntent = shouldUseWeatherTool(message);
        boolean useSearch = shouldUseSearch(message, requestedSearch) || weatherIntent;
        AgentMode mode = selectAgentMode(message, weatherIntent, useSearch);
        AgentRunContext runContext = agentRunService.startRun(conversation, userMessage, mode, message, selectedModel);
        if (consumer != null) {
            emit(consumer, ChatStreamEvent.meta(selectedModel, useSearch, modelAvailable, List.of(), conversation.getId(), null, runContext.run().getId()));
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
            weatherReport = runReactToolLoop(runContext, message, requestedSearch, weatherIntent, useSearch, sources, consumer);
        } else if (useSearch) {
            if (consumer != null) {
                emit(consumer, ChatStreamEvent.status("searching", "检索中"));
            }
            AgentStepResponse searchStep = addAgentStep(runContext, "action", "调用实时检索", "query=" + message, "completed", consumer);
            Instant startedAt = Instant.now();
            List<SearchResult> searchResults = searchSafely(message, 5);
            sources.addAll(searchResults);
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

        return new PreparedAgentContext(
                runContext,
                mode,
                useSearch,
                weatherReport,
                deduplicateSources(sources),
                memories,
                plan
        );
    }

    private WeatherReport runReactToolLoop(AgentRunContext runContext,
                                           String message,
                                           Boolean requestedSearch,
                                           boolean weatherIntent,
                                           boolean useSearch,
                                           List<SearchResult> sources,
                                           ChatStreamConsumer consumer) throws IOException {
        int actionSteps = 0;
        int maxSteps = runContext.run().getMaxSteps();
        WeatherReport weatherReport = null;

        addAgentStep(
                runContext,
                "thought",
                "ReAct 思考",
                "根据问题判断是否需要天气工具、实时检索或降级检索；达到最大步数后停止继续调用工具。",
                "completed",
                consumer
        );

        if (weatherIntent && actionSteps < maxSteps) {
            actionSteps++;
            String weatherLocation = extractWeatherLocation(message);
            String weatherDate = extractWeatherDate(message);
            AgentStepResponse weatherStep = addAgentStep(
                    runContext,
                    "action",
                    "调用天气工具",
                    "get_weather(location=%s, date=%s)".formatted(weatherLocation, weatherDate),
                    "completed",
                    consumer
            );
            Instant startedAt = Instant.now();
            weatherReport = weatherTools.getWeatherReport(weatherLocation, weatherDate);
            sources.addAll(weatherReport.sources());
            agentRunService.recordToolCall(
                    runContext,
                    weatherStep,
                    "get_weather",
                    Map.of("location", weatherLocation, "date", weatherDate),
                    weatherReport.context(),
                    weatherReport.available() ? "completed" : "failed",
                    startedAt
            );
            addAgentStep(
                    runContext,
                    "observation",
                    "天气工具结果",
                    StringUtils.hasText(weatherReport.context()) ? weatherReport.context() : weatherReport.summary(),
                    weatherReport.available() ? "completed" : "failed",
                    consumer
            );
        }

        boolean shouldRunSearch = useSearch && (!weatherIntent || Boolean.TRUE.equals(requestedSearch));
        if (shouldRunSearch && actionSteps < maxSteps) {
            actionSteps++;
            AgentStepResponse searchStep = addAgentStep(runContext, "action", "调用实时检索", "query=" + message, "completed", consumer);
            Instant startedAt = Instant.now();
            List<SearchResult> searchResults = searchSafely(message, 5);
            sources.addAll(searchResults);
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

        if (shouldSearchWeatherFallback(weatherIntent, extractWeatherLocation(message), weatherReport) && actionSteps < maxSteps) {
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
            List<SearchResult> fallbackResults = searchSafely(message, 5);
            sources.addAll(fallbackResults);
            agentRunService.recordToolCall(
                    runContext,
                    fallbackStep,
                    "web_search",
                    Map.of("query", message, "reason", "weather_fallback", "limit", 5),
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

            String supplement = "\n\n补充修正：\n" + improvement.trim();
            currentAnswer += supplement;
            if (streaming && consumer != null) {
                emitUnchecked(consumer, ChatStreamEvent.status("answering", "回答中"));
                emitUnchecked(consumer, ChatStreamEvent.delta(supplement));
            }
        }

        return currentAnswer;
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
        return new ChatResponse(
                answer,
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
        MessageEntity savedMessage = conversationService.saveAssistantMessage(
                conversation,
                answer,
                model,
                realtimeSearchUsed,
                modelAvailable,
                sources
        );
        refreshConversationSummaryIfNeeded(conversation, model);
        memoryService.rememberConversationTurn(
                conversation.getId(),
                savedMessage.getId(),
                userMessage,
                answer,
                model,
                sources == null ? 0 : sources.size()
        );
        if (runContext != null) {
            agentRunService.completeRun(runContext, savedMessage, answer);
        }
        return savedMessage;
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

    private boolean shouldSearchWeatherFallback(boolean weatherIntent, String weatherLocation, WeatherReport weatherReport) {
        return weatherIntent
                && StringUtils.hasText(weatherLocation)
                && weatherReport != null
                && !weatherReport.available();
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
