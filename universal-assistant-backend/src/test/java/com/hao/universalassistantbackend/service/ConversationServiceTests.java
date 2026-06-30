package com.hao.universalassistantbackend.service;

import com.hao.universalassistantbackend.entity.ConversationEntity;
import com.hao.universalassistantbackend.entity.MessageEntity;
import com.hao.universalassistantbackend.model.AgentMode;
import com.hao.universalassistantbackend.model.AgentRunContext;
import com.hao.universalassistantbackend.model.ConversationMessageResponse;
import com.hao.universalassistantbackend.model.ConversationMessagesResponse;
import com.hao.universalassistantbackend.model.SearchResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class ConversationServiceTests {

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ChatService chatService;

    @Autowired
    private AgentRunService agentRunService;

    @Test
    void editResendKeepsFinalizedAssistantMessageVisible() {
        ConversationEntity conversation = conversationService.getOrCreateConversation(null, "旧问题");
        MessageEntity userMessage = conversationService.saveUserMessage(conversation, "旧问题");
        conversationService.saveAssistantMessage(conversation, "旧回复", "deepseek-v4-pro", false, true, List.of());

        conversationService.editUserMessageAndInvalidateAfter(
                conversation.getId().toString(),
                userMessage.getId().toString(),
                "新问题"
        );
        MessageEntity assistantMessage = conversationService.saveAssistantMessage(
                conversation,
                "新回复",
                "deepseek-v4-pro",
                false,
                true,
                List.of()
        );

        ConversationMessagesResponse response = conversationService.getMessages(conversation.getId().toString());
        List<ConversationMessageResponse> messages = response.messages();

        assertEquals(2, messages.size());
        assertEquals("新问题", messages.get(0).content());
        assertEquals("新回复", messages.get(1).content());
        assertEquals("completed", messages.get(1).status());
        assertFalse(messages.stream().anyMatch(message -> "旧回复".equals(message.content())));
        assertTrue(messages.stream().anyMatch(message -> assistantMessage.getId().equals(message.id())));
    }

    @Test
    void unfinishedAgentRunRestoresPartialAnswerWithoutAssistantMessage() {
        ConversationEntity conversation = conversationService.getOrCreateConversation(null, "查天气");
        MessageEntity userMessage = conversationService.saveUserMessage(conversation, "查天气");
        AgentRunContext runContext = agentRunService.startRun(conversation, userMessage, AgentMode.REACT, "查天气", "deepseek-v4-pro");
        agentRunService.updateProgress(runContext, "正在查询广州天气。", true, true, List.of());

        ConversationMessagesResponse response = conversationService.getMessages(conversation.getId().toString());
        List<ConversationMessageResponse> messages = response.messages();

        assertEquals(2, messages.size());
        assertEquals("查天气", messages.get(0).content());
        assertEquals("正在查询广州天气。", messages.get(1).content());
        assertEquals("running", messages.get(1).status());
        assertEquals(runContext.run().getId(), messages.get(1).id());
        assertEquals(runContext.run().getId(), messages.get(1).agentRunId());
    }

    @Test
    void finalizingAssistantMessageNeverStoresEmptyCompletedContent() {
        ConversationEntity conversation = conversationService.getOrCreateConversation(null, "查天气");
        List<SearchResult> sources = List.of(new SearchResult("广州天气", "https://example.com/weather", "天气参考"));

        MessageEntity savedMessage = ReflectionTestUtils.invokeMethod(
                chatService,
                "saveAssistantMessageAndFinalize",
                conversation,
                "",
                "deepseek-v4-pro",
                true,
                true,
                sources,
                null,
                "查天气"
        );

        assertTrue(savedMessage != null && savedMessage.getContent().contains("没有收到模型正文"));
    }
}
