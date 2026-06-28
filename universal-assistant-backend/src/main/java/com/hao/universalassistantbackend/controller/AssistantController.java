package com.hao.universalassistantbackend.controller;

import com.hao.universalassistantbackend.model.ChatRequest;
import com.hao.universalassistantbackend.model.ChatResponse;
import com.hao.universalassistantbackend.model.SearchResponse;
import com.hao.universalassistantbackend.service.ChatService;
import com.hao.universalassistantbackend.service.SearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class AssistantController {

    private final ChatService chatService;
    private final SearchService searchService;

    public AssistantController(ChatService chatService, SearchService searchService) {
        this.chatService = chatService;
        this.searchService = searchService;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody(required = false) ChatRequest request) {
        return ResponseEntity.ok(chatService.chat(request));
    }

    @GetMapping("/search")
    public ResponseEntity<SearchResponse> search(@RequestParam("q") String query) {
        if (!StringUtils.hasText(query)) {
            return ResponseEntity.badRequest().body(new SearchResponse(query, java.util.List.of()));
        }

        return ResponseEntity.ok(new SearchResponse(query, searchService.search(query, 5)));
    }
}
