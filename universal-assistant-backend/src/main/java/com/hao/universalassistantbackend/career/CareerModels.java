package com.hao.universalassistantbackend.career;

import com.hao.universalassistantbackend.model.SearchResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CareerModels {

    private CareerModels() {
    }

    public record CreateProfileRequest(String name) {
    }

    public record UpdateProfileRequest(String name) {
    }

    public record CareerProfile(UUID id, String name, UUID knowledgeBaseId, Instant createdAt, Instant updatedAt) {
    }

    public record ImportResumeRequest(UUID profileId, String title, String content, String sourceFormat) {
        public ImportResumeRequest(UUID profileId, String title, String content) {
            this(profileId, title, content, null);
        }
    }

    public record ParsedResume(String fileName, String format, String content, List<String> warnings) {
    }

    public record ResumeVersion(
            UUID id,
            UUID profileId,
            UUID knowledgeDocumentId,
            UUID parentVersionId,
            int versionNumber,
            String title,
            String content,
            String sourceType,
            String sourceFormat,
            Instant createdAt
    ) {
    }

    public record CreateJobTargetRequest(
            UUID profileId,
            String jobTitle,
            String company,
            String description,
            String sourceUri
    ) {
    }

    public record JobTarget(
            UUID id,
            UUID profileId,
            UUID knowledgeDocumentId,
            String jobTitle,
            String company,
            String description,
            String sourceUri,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record AnalyzeResumeRequest(UUID profileId, UUID resumeVersionId, UUID jobTargetId, boolean realtimeResearch) {
    }

    public record RequirementMatch(String requirement, String status, String evidence, String recommendation) {
    }

    public record ResumeChange(
            UUID id,
            int index,
            String section,
            String originalText,
            String suggestedText,
            String reason,
            List<String> evidence,
            boolean evidenceVerified,
            List<String> verificationIssues,
            String status
    ) {
    }

    public record ResumeAnalysis(
            UUID changeSetId,
            String summary,
            int matchScore,
            List<RequirementMatch> requirements,
            List<ResumeChange> changes,
            List<SearchResult> sources,
            String status
    ) {
    }

    public record ResumeAnalysisSummary(
            UUID changeSetId,
            UUID resumeVersionId,
            UUID jobTargetId,
            String resumeTitle,
            String jobTitle,
            String company,
            int matchScore,
            String status,
            Instant createdAt
    ) {
    }

    public record UpdateResumeChangeRequest(String decision) {
    }

    public record StartInterviewRequest(
            UUID profileId,
            UUID resumeVersionId,
            UUID jobTargetId,
            String mode,
            Integer maxQuestions
    ) {
    }

    public record AnswerInterviewRequest(String answer) {
    }

    public record InterviewQuestion(int index, String category, String question, String focus) {
    }

    public record InterviewFeedback(
            int score,
            String summary,
            List<String> strengths,
            List<String> improvements,
            String exampleAnswer
    ) {
    }

    public record InterviewTurn(
            int index,
            String question,
            String answer,
            InterviewFeedback feedback,
            Instant createdAt
    ) {
    }

    public record InterviewSession(
            UUID id,
            UUID profileId,
            UUID resumeVersionId,
            UUID jobTargetId,
            String mode,
            String status,
            int currentIndex,
            int maxQuestions,
            InterviewQuestion currentQuestion,
            InterviewFeedback latestFeedback,
            List<InterviewTurn> turns,
            String finalReport,
            Instant startedAt,
            Instant completedAt
    ) {
    }

    public record InterviewSessionSummary(
            UUID id,
            UUID resumeVersionId,
            UUID jobTargetId,
            String resumeTitle,
            String jobTitle,
            String company,
            String mode,
            String status,
            int currentIndex,
            int maxQuestions,
            Instant startedAt,
            Instant completedAt
    ) {
    }
}
