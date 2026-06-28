package com.hao.universalassistantbackend;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class UniversalAssistantBackendApplicationTests {

    @Autowired
    ChatModel deepSeek;

    @Test
    void contextLoads() {
        // // 创建 agent
        ReactAgent agent = ReactAgent.builder()
                .name("weather_agent")
                .model(deepSeek)
                .systemPrompt("You are a helpful assistant")
                .build();

        // 运行 agent
        AssistantMessage response = null;
        try {
            response = agent.call("what is the weather in San Francisco");
        } catch (GraphRunnerException e) {
            e.printStackTrace();
        }
        System.out.println(response.getText());

        System.out.println(System.getenv("DASHSCOPE_API_KEY"));
    }

}
