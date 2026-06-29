package com.hao.universalassistantbackend.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LLMConfig {
    @Value("${spring.ai.dashscope.api-key:missing-api-key}")
    private String dashScopeApiKey;

    @Value("${assistant.chat.max-tokens:2400}")
    private int maxTokens;

    @Bean(name = "deepSeek")
    public ChatModel deepSeek() {
        return DashScopeChatModel.builder()
                .dashScopeApi(DashScopeApi.builder().apiKey(dashScopeApiKey).build())
                .defaultOptions(DashScopeChatOptions.builder()
                        // Note: model must be set when use options build.
                        .model("deepseek-v4-pro")
                        .temperature(0.5)
                        .maxToken(maxTokens)
                        .build())
                .build();
    }

    @Bean(name = "deepSeekChatClient")
    public ChatClient deepSeekChatClient(@Qualifier("deepSeek") ChatModel deepseek) {
        return ChatClient.builder(deepseek).build();
    }
}
