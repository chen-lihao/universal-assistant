package com.hao.universalassistantbackend.career;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hao.universalassistantbackend.model.SearchResult;
import com.hao.universalassistantbackend.rag.KnowledgeDocumentRequest;
import com.hao.universalassistantbackend.rag.KnowledgeDocumentResponse;
import com.hao.universalassistantbackend.rag.KnowledgeHit;
import com.hao.universalassistantbackend.rag.KnowledgeService;
import com.hao.universalassistantbackend.service.SearchService;
import com.hao.universalassistantbackend.skill.ActiveSkill;
import com.hao.universalassistantbackend.skill.SkillService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.hao.universalassistantbackend.career.CareerModels.*;

@Service
public class CareerService {

    private static final String MODEL = "deepseek-v4-pro";
    private static final int MAX_INPUT_CHARS = 24_000;

    private final CareerRepository repository;
    private final KnowledgeService knowledgeService;
    private final SearchService searchService;
    private final SkillService skillService;
    private final CareerPrivacyService privacyService;
    private final ObjectProvider<ChatClient> chatClientProvider;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public CareerService(CareerRepository repository,
                         KnowledgeService knowledgeService,
                         SearchService searchService,
                         SkillService skillService,
                         CareerPrivacyService privacyService,
                         @Qualifier("deepSeekChatClient") ObjectProvider<ChatClient> chatClientProvider,
                         ObjectMapper objectMapper,
                         @Value("${spring.ai.dashscope.api-key:}") String apiKey) {
        this.repository = repository;
        this.knowledgeService = knowledgeService;
        this.searchService = searchService;
        this.skillService = skillService;
        this.privacyService = privacyService;
        this.chatClientProvider = chatClientProvider;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
    }

    public List<CareerProfile> listProfiles() {
        return repository.listProfiles();
    }

    public CareerProfile createProfile(CreateProfileRequest request) {
        return repository.createProfile(request == null ? null : request.name());
    }

    public CareerProfile updateProfile(UUID profileId, UpdateProfileRequest request) {
        profile(profileId);
        require(request != null && StringUtils.hasText(request.name()), "档案名称不能为空。");
        return repository.updateProfile(profileId, request.name().trim());
    }

    @Transactional
    public CareerProfile deleteProfile(UUID profileId) {
        CareerProfile profile = profile(profileId);
        repository.deleteProfile(profile.id(), profile.knowledgeBaseId());
        return profile;
    }

    @Transactional
    public ResumeVersion importResume(ImportResumeRequest request) {
        require(request != null && request.profileId() != null, "请选择求职档案。");
        require(StringUtils.hasText(request.content()), "简历内容不能为空。");
        require(request.content().length() <= 100_000, "简历文字不能超过 10 万字。");
        String format = StringUtils.hasText(request.sourceFormat()) ? request.sourceFormat().toLowerCase() : "text";
        require(List.of("text", "md", "pdf", "docx", "doc").contains(format), "不支持的简历来源格式。");
        CareerProfile profile = profile(request.profileId());
        String title = StringUtils.hasText(request.title()) ? request.title().trim() : "基础简历";
        KnowledgeDocumentResponse document = knowledgeService.ingest(new KnowledgeDocumentRequest(
                profile.knowledgeBaseId().toString(),
                title,
                null,
                privacyService.redactForExternalProcessing(request.content()),
                Map.of("documentType", "resume", "profileId", profile.id().toString(), "verified", true)
        ));
        return repository.saveResume(profile.id(), document.id(), null, title, request.content().trim(), "imported", format);
    }

    public List<ResumeVersion> listResumes(UUID profileId) {
        profile(profileId);
        return repository.listResumes(profileId);
    }

    @Transactional
    public JobTarget createJobTarget(CreateJobTargetRequest request) {
        require(request != null && request.profileId() != null, "请选择求职档案。");
        require(StringUtils.hasText(request.jobTitle()), "目标岗位不能为空。");
        require(StringUtils.hasText(request.description()), "岗位描述不能为空。");
        CareerProfile profile = profile(request.profileId());
        String documentTitle = (StringUtils.hasText(request.company()) ? request.company().trim() + " - " : "") + request.jobTitle().trim();
        KnowledgeDocumentResponse document = knowledgeService.ingest(new KnowledgeDocumentRequest(
                profile.knowledgeBaseId().toString(),
                documentTitle,
                request.sourceUri(),
                request.description(),
                Map.of("documentType", "job_description", "profileId", profile.id().toString(), "verified", true)
        ));
        return repository.saveJobTarget(
                profile.id(), document.id(), request.jobTitle().trim(), request.company(),
                request.description().trim(), request.sourceUri()
        );
    }

    public List<JobTarget> listJobTargets(UUID profileId) {
        profile(profileId);
        return repository.listJobTargets(profileId);
    }

    public ResumeAnalysis analyzeResume(AnalyzeResumeRequest request) {
        require(request != null, "分析请求不能为空。");
        CareerProfile profile = profile(request.profileId());
        ResumeVersion resume = resume(request.resumeVersionId());
        JobTarget target = target(request.jobTargetId());
        require(profile.id().equals(resume.profileId()) && profile.id().equals(target.profileId()), "简历和岗位不属于当前求职档案。");

        List<KnowledgeHit> hits = knowledgeService.search(
                target.jobTitle() + " " + target.description(),
                profile.knowledgeBaseId()
        );
        List<SearchResult> sources = new ArrayList<>(hits.stream().map(KnowledgeHit::toSearchResult).toList());
        if (request.realtimeResearch() && StringUtils.hasText(target.company())) {
            sources.addAll(searchService.search(target.company() + " " + target.jobTitle() + " 招聘 要求", 4));
        }

        ResumeAnalysisDraft draft = generateAnalysis(resume, target, hits, sources);
        List<CareerRepository.ResumeChangeDraft> verifiedChanges = draft.changes().stream()
                .limit(12)
                .map(change -> verifyChange(change, resume.content()))
                .toList();
        UUID changeSetId = repository.saveAnalysis(
                profile.id(), resume.id(), target.id(), draft.summary(), clampScore(draft.matchScore()),
                draft.requirements(), sources, verifiedChanges
        );
        return repository.findAnalysis(changeSetId).orElseThrow();
    }

    public ResumeAnalysis getAnalysis(UUID changeSetId) {
        return repository.findAnalysis(changeSetId).orElseThrow(() -> new IllegalArgumentException("简历分析不存在。"));
    }

    public List<ResumeAnalysisSummary> listAnalyses(UUID profileId) {
        profile(profileId);
        return repository.listAnalyses(profileId);
    }

    public ResumeChange updateChange(UUID changeId, UpdateResumeChangeRequest request) {
        require(request != null && StringUtils.hasText(request.decision()), "请选择接受或拒绝。" );
        return repository.updateChange(changeId, request.decision());
    }

    @Transactional
    public ResumeVersion applyAcceptedChanges(UUID changeSetId) {
        CareerRepository.ChangeSetContext context = repository.findChangeSetContext(changeSetId);
        CareerProfile profile = profile(context.profileId());
        ResumeVersion source = resume(context.resumeVersionId());
        List<ResumeChange> accepted = repository.listChanges(changeSetId).stream()
                .filter(change -> "accepted".equals(change.status()) && change.evidenceVerified())
                .toList();
        require(!accepted.isEmpty(), "请先接受至少一条有证据的修改。" );

        String revised = source.content();
        int applied = 0;
        for (ResumeChange change : accepted) {
            int index = revised.indexOf(change.originalText());
            if (index >= 0) {
                revised = revised.substring(0, index)
                        + change.suggestedText()
                        + revised.substring(index + change.originalText().length());
                applied++;
            }
        }
        require(applied > 0, "接受的修改无法在当前简历版本中定位，请重新分析。" );

        String title = source.title() + " - 定制版";
        KnowledgeDocumentResponse document = knowledgeService.ingest(new KnowledgeDocumentRequest(
                profile.knowledgeBaseId().toString(),
                title,
                null,
                privacyService.redactForExternalProcessing(revised),
                Map.of(
                        "documentType", "resume",
                        "profileId", profile.id().toString(),
                        "parentVersionId", source.id().toString(),
                        "verified", true
                )
        ));
        ResumeVersion version = repository.saveResume(
                profile.id(), document.id(), source.id(), title, revised, "tailored", "text"
        );
        repository.completeChangeSet(changeSetId);
        return version;
    }

    private ResumeAnalysisDraft generateAnalysis(ResumeVersion resume,
                                                  JobTarget target,
                                                  List<KnowledgeHit> hits,
                                                  List<SearchResult> sources) {
        ChatClient client = configuredClient();
        if (client == null) {
            return fallbackAnalysis(target);
        }
        String prompt = buildAnalysisPrompt(resume, target, hits, sources);
        try {
            String result = client.prompt()
                    .system("""
                            You are a strict resume reviewer. Return only valid JSON, without markdown fences.
                            Never invent employers, dates, technologies, responsibilities, metrics, certifications, or achievements.
                            Every proposed rewrite must preserve the exact factual meaning of quoted evidence.
                            A job-description keyword is not candidate evidence. Do not add technologies, methods, ownership verbs, or responsibilities unless those exact claims already appear in the resume.
                            Keep originalText and each evidence quote verbatim from the resume. Do not add new Latin terms, numbers, or responsibility claims in suggestedText unless they already appear in the resume.
                            Use Chinese. The response schema is:
                            {"summary":"...","matchScore":0,"requirements":[{"requirement":"...","status":"matched|partial|missing","evidence":"...","recommendation":"..."}],"changes":[{"section":"...","originalText":"exact substring from resume","suggestedText":"...","reason":"...","evidence":["exact quote from supplied evidence"]}]}
                            Provide at most 10 requirements and 8 changes. If evidence is insufficient, report a missing requirement instead of fabricating a rewrite.
                            """)
                    .options(DashScopeChatOptions.builder().model(MODEL).temperature(0.2).maxToken(3200).build())
                    .user(prompt)
                    .call()
                    .content();
            ResumeAnalysisDraft parsed = readModelJson(result, ResumeAnalysisDraft.class);
            if (parsed != null) {
                return normalizeDraft(parsed);
            }
        } catch (RuntimeException ignored) {
            // Fall back to a deterministic result so the workflow remains usable.
        }
        return fallbackAnalysis(target);
    }

    private String buildAnalysisPrompt(ResumeVersion resume,
                                       JobTarget target,
                                       List<KnowledgeHit> hits,
                                       List<SearchResult> sources) {
        StringBuilder prompt = new StringBuilder();
        List<ActiveSkill> skills = skillService.selectRelevant("简历审阅 岗位匹配 简历修改");
        for (ActiveSkill skill : skills) {
            prompt.append("Skill ").append(skill.name()).append(":\n")
                    .append(trim(skill.instructions(), 4500)).append("\n\n");
        }
        prompt.append("Target role: ").append(target.jobTitle()).append('\n')
                .append("Company: ").append(target.company() == null ? "未指定" : target.company()).append("\n\n")
                .append("Job description:\n").append(trim(target.description(), 7000)).append("\n\n")
                .append("Resume (the only valid source for personal claims):\n")
                .append(trim(privacyService.redactForExternalProcessing(resume.content()), 11000)).append("\n\n")
                .append("Additional verified evidence:\n");
        for (KnowledgeHit hit : hits) {
            prompt.append("- ").append(hit.title()).append(": ").append(trim(hit.content(), 1000)).append('\n');
        }
        if (!sources.isEmpty()) {
            prompt.append("\nCompany research is context only and must never be used as evidence for candidate experience:\n");
            sources.stream().filter(source -> !"knowledge".equals(source.provider())).forEach(source -> prompt
                    .append("- ").append(source.title()).append(": ").append(trim(source.snippet(), 500)).append('\n'));
        }
        return trim(prompt.toString(), MAX_INPUT_CHARS);
    }

    private CareerRepository.ResumeChangeDraft verifyChange(ResumeChangeDraftModel change, String resumeContent) {
        String original = change.originalText() == null ? "" : change.originalText().trim();
        String locatedOriginal = CareerEvidencePolicy.locateOriginal(original, resumeContent);
        List<String> evidence = change.evidence() == null ? List.of() : change.evidence().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .limit(5)
                .toList();
        List<String> issues = new ArrayList<>();
        if (locatedOriginal == null) issues.add("无法在原始简历中定位待修改的句子。");
        if (evidence.isEmpty()) issues.add("没有提供对应的简历证据引用。");
        else if (evidence.stream().anyMatch(item -> CareerEvidencePolicy.locateOriginal(item, resumeContent) == null)) {
            issues.add("证据引用与原始简历不一致。");
        }
        issues.addAll(CareerEvidencePolicy.claimIssues(change.suggestedText(), resumeContent));
        return new CareerRepository.ResumeChangeDraft(
                text(change.section(), "未分类"),
                locatedOriginal == null ? original : locatedOriginal,
                text(change.suggestedText(), original),
                text(change.reason(), "提升与目标岗位的相关性"),
                evidence,
                issues.isEmpty(),
                issues
        );
    }

    private ResumeAnalysisDraft normalizeDraft(ResumeAnalysisDraft draft) {
        return new ResumeAnalysisDraft(
                text(draft.summary(), "已完成简历与岗位要求的证据化匹配。"),
                clampScore(draft.matchScore()),
                draft.requirements() == null ? List.of() : draft.requirements().stream().limit(10).toList(),
                draft.changes() == null ? List.of() : draft.changes().stream().limit(8).toList()
        );
    }

    private ResumeAnalysisDraft fallbackAnalysis(JobTarget target) {
        Set<String> requirements = new LinkedHashSet<>();
        for (String line : target.description().split("[\\n。；;]")) {
            if (line.trim().length() >= 6) {
                requirements.add(line.trim());
            }
            if (requirements.size() >= 8) {
                break;
            }
        }
        List<RequirementMatch> matrix = requirements.stream()
                .map(requirement -> new RequirementMatch(requirement, "partial", "需要模型或人工核对", "补充可验证的经历证据"))
                .toList();
        return new ResumeAnalysisDraft(
                "模型当前不可用，已提取岗位要求；未生成可能失真的改写建议。",
                0,
                matrix,
                List.of()
        );
    }

    private CareerProfile profile(UUID id) {
        require(id != null, "求职档案不能为空。" );
        return repository.findProfile(id).orElseThrow(() -> new IllegalArgumentException("求职档案不存在。"));
    }

    private ResumeVersion resume(UUID id) {
        require(id != null, "简历版本不能为空。" );
        return repository.findResume(id).orElseThrow(() -> new IllegalArgumentException("简历版本不存在。"));
    }

    private JobTarget target(UUID id) {
        require(id != null, "目标岗位不能为空。" );
        return repository.findJobTarget(id).orElseThrow(() -> new IllegalArgumentException("目标岗位不存在。"));
    }

    private ChatClient configuredClient() {
        if (!StringUtils.hasText(apiKey) || "missing-api-key".equals(apiKey)) {
            return null;
        }
        return chatClientProvider.getIfAvailable();
    }

    private <T> T readModelJson(String value, Class<T> type) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        int start = normalized.indexOf('{');
        int end = normalized.lastIndexOf('}');
        if (start >= 0 && end > start) {
            normalized = normalized.substring(start, end + 1);
        }
        try {
            return objectMapper.readValue(normalized, type);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private int clampScore(int score) {
        return Math.max(0, Math.min(100, score));
    }

    private String trim(String value, int max) {
        if (value == null || value.length() <= max) {
            return value == null ? "" : value;
        }
        return value.substring(0, max - 1) + "…";
    }

    private String text(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private record ResumeAnalysisDraft(
            String summary,
            int matchScore,
            List<RequirementMatch> requirements,
            List<ResumeChangeDraftModel> changes
    ) {
    }

    private record ResumeChangeDraftModel(
            String section,
            String originalText,
            String suggestedText,
            String reason,
            List<String> evidence
    ) {
    }
}
