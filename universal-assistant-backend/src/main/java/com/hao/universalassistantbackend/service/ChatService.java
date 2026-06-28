package com.hao.universalassistantbackend.service;

import com.hao.universalassistantbackend.model.ChatMessage;
import com.hao.universalassistantbackend.model.ChatRequest;
import com.hao.universalassistantbackend.model.ChatResponse;
import com.hao.universalassistantbackend.model.SearchResult;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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
    private static final Pattern LEADING_MODEL_COMMAND = Pattern.compile(
            "^\\s*(?:/model\\s+|@)([a-zA-Z0-9_.-]+)(?:\\s+|$)",
            Pattern.CASE_INSENSITIVE
    );

    private static final String SYSTEM_PROMPT = """
            You are Universal Assistant, a desktop AI assistant.
            Answer in Chinese by default unless the user asks for another language.
            Be concise, practical, and honest about uncertainty.
            When search context is provided, use it and cite source links naturally.
            For local file operations, explain the intended operation and remind the user that the desktop app must confirm writes.
            """;

    private final ObjectProvider<ChatClient> chatClientProvider;
    private final SearchService searchService;
    private final String dashScopeApiKey;

    public ChatService(@Qualifier("deepSeekChatClient") ObjectProvider<ChatClient> chatClientProvider,
                       SearchService searchService,
                       @Value("${spring.ai.dashscope.api-key:}") String dashScopeApiKey) {
        this.chatClientProvider = chatClientProvider;
        this.searchService = searchService;
        this.dashScopeApiKey = dashScopeApiKey;
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
        List<ChatMessage> history = request == null ? List.of() : request.history();
        boolean useSearch = shouldUseSearch(message, requestedSearch);
        List<SearchResult> sources = useSearch ? searchService.search(message, 5) : List.of();

        if (!isModelConfigured()) {
            return new ChatResponse(fallbackAnswerWithoutModel(sources), useSearch, false, selectedModel, sources);
        }

        try {
            ChatClient chatClient = chatClientProvider.getIfAvailable();
            if (chatClient == null) {
                return new ChatResponse(fallbackAnswerWithoutModel(sources), useSearch, false, selectedModel, sources);
            }

            String answer = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .options(DashScopeChatOptions.builder()
                            .model(selectedModel)
                            .temperature(0.5)
                            .maxToken(1000)
                            .build())
                    .user(buildUserPrompt(message, history, sources))
                    .call()
                    .content();

            if (!StringUtils.hasText(answer)) {
                answer = fallbackAnswerWithoutModel(sources);
            }

            return new ChatResponse(answer, useSearch, true, selectedModel, sources);
        } catch (RuntimeException ex) {
            String answer = "模型调用失败：" + ex.getMessage();
            if (!sources.isEmpty()) {
                answer += "\n\n" + fallbackSearchSummary(sources);
            }
            return new ChatResponse(answer, useSearch, false, selectedModel, sources);
        }
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
                || normalized.contains("价格")
                || normalized.contains("latest")
                || normalized.contains("today")
                || normalized.contains("current")
                || normalized.contains("news");
    }

    private boolean isModelConfigured() {
        return StringUtils.hasText(dashScopeApiKey) && !"missing-api-key".equals(dashScopeApiKey);
    }

    private String buildUserPrompt(String message, List<ChatMessage> history, List<SearchResult> sources) {
        StringBuilder prompt = new StringBuilder();

        if (history != null && !history.isEmpty()) {
            prompt.append("Recent conversation:\n");
            history.stream()
                    .filter(item -> item != null && StringUtils.hasText(item.content()))
                    .limit(8)
                    .forEach(item -> prompt
                            .append("- ")
                            .append(StringUtils.hasText(item.role()) ? item.role() : "unknown")
                            .append(": ")
                            .append(item.content())
                            .append('\n'));
            prompt.append('\n');
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

        prompt.append("User question:\n").append(message);
        return prompt.toString();
    }

    private String fallbackAnswerWithoutModel(List<SearchResult> sources) {
        if (sources.isEmpty()) {
            return "后端已启动，但还没有配置 DASHSCOPE_API_KEY，暂时无法调用大模型。设置环境变量后重启后端即可使用聊天能力。";
        }

        return "后端还没有配置 DASHSCOPE_API_KEY，暂时无法调用大模型生成综合回答。\n\n" + fallbackSearchSummary(sources);
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
