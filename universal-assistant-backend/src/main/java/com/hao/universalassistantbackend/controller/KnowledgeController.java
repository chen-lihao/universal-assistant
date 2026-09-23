package com.hao.universalassistantbackend.controller;

import com.hao.universalassistantbackend.rag.KnowledgeDocumentDetail;
import com.hao.universalassistantbackend.rag.KnowledgeDocumentRequest;
import com.hao.universalassistantbackend.rag.KnowledgeDocumentResponse;
import com.hao.universalassistantbackend.rag.KnowledgeHit;
import com.hao.universalassistantbackend.rag.KnowledgeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @PostMapping("/documents")
    public ResponseEntity<?> ingest(@RequestBody KnowledgeDocumentRequest request) {
        try {
            return ResponseEntity.ok(knowledgeService.ingest(request));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping("/documents")
    public List<KnowledgeDocumentResponse> listDocuments() {
        return knowledgeService.listDocuments();
    }

    @GetMapping("/documents/{documentId}")
    public ResponseEntity<KnowledgeDocumentDetail> getDocument(@PathVariable UUID documentId) {
        return knowledgeService.getDocument(documentId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/search")
    public List<KnowledgeHit> search(@RequestParam("q") String query) {
        return knowledgeService.search(query);
    }
}
