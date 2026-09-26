package com.hao.universalassistantbackend.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hao.universalassistantbackend.career.CareerModels.AnalyzeResumeRequest;
import com.hao.universalassistantbackend.career.CareerModels.StartInterviewRequest;
import com.hao.universalassistantbackend.career.CareerRepository;
import com.hao.universalassistantbackend.career.CareerPrivacyService;
import com.hao.universalassistantbackend.career.CareerService;
import com.hao.universalassistantbackend.career.InterviewService;
import com.hao.universalassistantbackend.rag.KnowledgeService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class CareerTools {

    private final CareerRepository repository;
    private final KnowledgeService knowledgeService;
    private final CareerService careerService;
    private final InterviewService interviewService;
    private final CareerPrivacyService privacyService;
    private final ObjectMapper objectMapper;

    public CareerTools(CareerRepository repository,
                       KnowledgeService knowledgeService,
                       CareerService careerService,
                       InterviewService interviewService,
                       CareerPrivacyService privacyService,
                       ObjectMapper objectMapper) {
        this.repository = repository;
        this.knowledgeService = knowledgeService;
        this.careerService = careerService;
        this.interviewService = interviewService;
        this.privacyService = privacyService;
        this.objectMapper = objectMapper;
    }

    @Tool(
            name = "career_evidence_search",
            description = "Search verified resume, job-description, project, and interview evidence in one career profile. Use this before making claims about a candidate's experience."
    )
    public String searchEvidence(
            @ToolParam(description = "Career profile UUID shown in the career workspace.") String profileId,
            @ToolParam(description = "Evidence query, for example a technology, responsibility, project, or job requirement.") String query
    ) {
        UUID id = uuid(profileId, "求职档案 ID 无效。");
        var profile = repository.findProfile(id)
                .orElseThrow(() -> new IllegalArgumentException("求职档案不存在。"));
        return privacyService.redactForExternalProcessing(
                json(knowledgeService.search(query, profile.knowledgeBaseId()))
        );
    }

    public String analyzeResume(
            String profileId,
            String resumeVersionId,
            String jobTargetId,
            Boolean realtimeResearch
    ) {
        return json(careerService.analyzeResume(new AnalyzeResumeRequest(
                uuid(profileId, "求职档案 ID 无效。"),
                uuid(resumeVersionId, "简历版本 ID 无效。"),
                uuid(jobTargetId, "目标岗位 ID 无效。"),
                Boolean.TRUE.equals(realtimeResearch)
        )));
    }

    public String startInterview(
            String profileId,
            String resumeVersionId,
            String jobTargetId,
            String mode,
            Integer maxQuestions
    ) {
        return json(interviewService.start(new StartInterviewRequest(
                uuid(profileId, "求职档案 ID 无效。"),
                uuid(resumeVersionId, "简历版本 ID 无效。"),
                uuid(jobTargetId, "目标岗位 ID 无效。"),
                mode,
                maxQuestions
        )));
    }

    private UUID uuid(String value, String message) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(message, ex);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return jsonError(ex);
        }
    }

    private String jsonError(JsonProcessingException ex) {
        try {
            return objectMapper.writeValueAsString(Map.of("error", "无法序列化工具结果。", "detail", ex.getOriginalMessage()));
        } catch (JsonProcessingException ignored) {
            return "{\"error\":\"无法序列化工具结果。\"}";
        }
    }
}
