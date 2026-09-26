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
            if (request != null && request.knowledgeBaseId() != null && !request.knowledgeBaseId().isBlank()
                    && !KnowledgeService.DEFAULT_KNOWLEDGE_BASE_ID.toString().equals(request.knowledgeBaseId())) {
                return ResponseEntity.badRequest().body(java.util.Map.of("error", "通用知识库接口只能写入默认知识库。"));
            }
            KnowledgeDocumentRequest scopedRequest = request == null ? null : new KnowledgeDocumentRequest(
                    KnowledgeService.DEFAULT_KNOWLEDGE_BASE_ID.toString(),
                    request.title(), request.sourceUri(), request.content(), request.metadata()
            );
            return ResponseEntity.ok(knowledgeService.ingest(scopedRequest));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping("/documents")
    public List<KnowledgeDocumentResponse> listDocuments() {
        return knowledgeService.listDocuments(KnowledgeService.DEFAULT_KNOWLEDGE_BASE_ID);
    }

    @GetMapping("/documents/{documentId}")
    public ResponseEntity<KnowledgeDocumentDetail> getDocument(@PathVariable UUID documentId) {
        return knowledgeService.getDocument(documentId, KnowledgeService.DEFAULT_KNOWLEDGE_BASE_ID)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/search")
    public List<KnowledgeHit> search(@RequestParam("q") String query) {
        return knowledgeService.search(query, KnowledgeService.DEFAULT_KNOWLEDGE_BASE_ID);
    }
}
