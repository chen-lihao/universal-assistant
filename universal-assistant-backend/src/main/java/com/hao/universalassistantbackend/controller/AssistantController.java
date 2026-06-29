package com.hao.universalassistantbackend.controller;

import com.hao.universalassistantbackend.model.ChatRequest;
import com.hao.universalassistantbackend.model.ChatResponse;
import com.hao.universalassistantbackend.model.ChatStreamEvent;
import com.hao.universalassistantbackend.model.ConversationMessagesResponse;
import com.hao.universalassistantbackend.model.ConversationResponse;
import com.hao.universalassistantbackend.model.CreateConversationRequest;
import com.hao.universalassistantbackend.model.SearchResponse;
import com.hao.universalassistantbackend.service.ChatService;
import com.hao.universalassistantbackend.service.ConversationService;
import com.hao.universalassistantbackend.service.SearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AssistantController {

    private final ChatService chatService;
    private final SearchService searchService;
    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;

    public AssistantController(ChatService chatService,
                               SearchService searchService,
                               ConversationService conversationService,
                               ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.searchService = searchService;
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody(required = false) ChatRequest request) {
        return ResponseEntity.ok(chatService.chat(request));
    }

    @GetMapping("/conversations")
    public ResponseEntity<List<ConversationResponse>> conversations() {
        return ResponseEntity.ok(conversationService.listConversations());
    }

    @PostMapping("/conversations")
    public ResponseEntity<ConversationResponse> createConversation(@RequestBody(required = false) CreateConversationRequest request) {
        return ResponseEntity.ok(conversationService.createConversation(request == null ? null : request.title()));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<ConversationMessagesResponse> conversationMessages(@PathVariable String conversationId) {
        return ResponseEntity.ok(conversationService.getMessages(conversationId));
    }

    @PostMapping(value = "/chat/stream", produces = "application/x-ndjson")
    public ResponseEntity<StreamingResponseBody> chatStream(@RequestBody(required = false) ChatRequest request) {
        StreamingResponseBody body = outputStream -> {
            OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
            chatService.streamChat(request, event -> {
                writer.write(objectMapper.writeValueAsString(event));
                writer.write('\n');
                writer.flush();
                outputStream.flush();
            });
        };

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-ndjson; charset=UTF-8"))
                .cacheControl(CacheControl.noCache())
                .header(HttpHeaders.CONNECTION, "keep-alive")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    @GetMapping("/search")
    public ResponseEntity<SearchResponse> search(@RequestParam("q") String query) {
        if (!StringUtils.hasText(query)) {
            return ResponseEntity.badRequest().body(new SearchResponse(query, java.util.List.of()));
        }

        return ResponseEntity.ok(new SearchResponse(query, searchService.search(query, 5)));
    }
}
