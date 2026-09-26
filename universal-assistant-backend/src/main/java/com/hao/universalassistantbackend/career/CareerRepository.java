package com.hao.universalassistantbackend.career;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hao.universalassistantbackend.model.SearchResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.hao.universalassistantbackend.career.CareerModels.*;

@Repository
public class CareerRepository {

    private static final TypeReference<List<RequirementMatch>> REQUIREMENTS_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<SearchResult>> SOURCES_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<InterviewQuestion>> QUESTIONS_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CareerRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CareerProfile createProfile(String requestedName) {
        UUID profileId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        String name = requestedName == null || requestedName.isBlank() ? "我的求职档案" : requestedName.trim();
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO knowledge_bases(id, name, description, enabled, created_at, updated_at) VALUES (?, ?, ?, TRUE, ?, ?)",
                knowledgeBaseId, name + "知识库", "简历、岗位、项目经历和面试资料", timestamp(now), timestamp(now)
        );
        jdbcTemplate.update(
                "INSERT INTO career_profiles(id, name, knowledge_base_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                profileId, name, knowledgeBaseId, timestamp(now), timestamp(now)
        );
        return new CareerProfile(profileId, name, knowledgeBaseId, now, now);
    }

    public List<CareerProfile> listProfiles() {
        return jdbcTemplate.query(
                "SELECT id, name, knowledge_base_id, created_at, updated_at FROM career_profiles ORDER BY updated_at DESC",
                (rs, rowNum) -> mapProfile(rs)
        );
    }

    public Optional<CareerProfile> findProfile(UUID profileId) {
        return queryOptional(
                "SELECT id, name, knowledge_base_id, created_at, updated_at FROM career_profiles WHERE id = ?",
                profileId,
                this::mapProfile
        );
    }

    @Transactional
    public CareerProfile updateProfile(UUID profileId, String name) {
        int updated = jdbcTemplate.update(
                "UPDATE career_profiles SET name = ?, updated_at = ? WHERE id = ?",
                name,
                timestamp(Instant.now()),
                profileId
        );
        if (updated == 0) {
            throw new IllegalArgumentException("求职档案不存在。");
        }
        return findProfile(profileId).orElseThrow();
    }

    @Transactional
    public void deleteProfile(UUID profileId, UUID knowledgeBaseId) {
        int deleted = jdbcTemplate.update("DELETE FROM career_profiles WHERE id = ?", profileId);
        if (deleted == 0) {
            throw new IllegalArgumentException("求职档案不存在。");
        }
        jdbcTemplate.update("DELETE FROM knowledge_bases WHERE id = ?", knowledgeBaseId);
    }

    @Transactional
    public ResumeVersion saveResume(UUID profileId,
                                    UUID knowledgeDocumentId,
                                    UUID parentVersionId,
                                    String title,
                                    String content,
                                    String sourceType,
                                    String sourceFormat) {
        Integer current = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version_number), 0) FROM resume_versions WHERE profile_id = ?",
                Integer.class,
                profileId
        );
        int version = (current == null ? 0 : current) + 1;
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update(
                """
                        INSERT INTO resume_versions(
                            id, profile_id, knowledge_document_id, parent_version_id, version_number,
                            title, content, source_type, source_format, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id, profileId, knowledgeDocumentId, parentVersionId, version, title, content, sourceType,
                sourceFormat, timestamp(now)
        );
        touchProfile(profileId);
        return new ResumeVersion(id, profileId, knowledgeDocumentId, parentVersionId, version, title, content,
                sourceType, sourceFormat, now);
    }

    public List<ResumeVersion> listResumes(UUID profileId) {
        return jdbcTemplate.query(
                """
                        SELECT id, profile_id, knowledge_document_id, parent_version_id, version_number,
                               title, content, source_type, source_format, created_at
                        FROM resume_versions WHERE profile_id = ? ORDER BY version_number DESC
                        """,
                (rs, rowNum) -> mapResume(rs),
                profileId
        );
    }

    public Optional<ResumeVersion> findResume(UUID resumeId) {
        return queryOptional(
                """
                        SELECT id, profile_id, knowledge_document_id, parent_version_id, version_number,
                               title, content, source_type, source_format, created_at
                        FROM resume_versions WHERE id = ?
                        """,
                resumeId,
                this::mapResume
        );
    }

    @Transactional
    public JobTarget saveJobTarget(UUID profileId,
                                   UUID knowledgeDocumentId,
                                   String jobTitle,
                                   String company,
                                   String description,
                                   String sourceUri) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update(
                """
                        INSERT INTO job_targets(
                            id, profile_id, knowledge_document_id, job_title, company, description,
                            source_uri, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id, profileId, knowledgeDocumentId, jobTitle, blankToNull(company), description,
                blankToNull(sourceUri), timestamp(now), timestamp(now)
        );
        touchProfile(profileId);
        return new JobTarget(id, profileId, knowledgeDocumentId, jobTitle, blankToNull(company), description, blankToNull(sourceUri), now, now);
    }

    public List<JobTarget> listJobTargets(UUID profileId) {
        return jdbcTemplate.query(
                """
                        SELECT id, profile_id, knowledge_document_id, job_title, company, description,
                               source_uri, created_at, updated_at
                        FROM job_targets WHERE profile_id = ? ORDER BY updated_at DESC
                        """,
                (rs, rowNum) -> mapJobTarget(rs),
                profileId
        );
    }

    public Optional<JobTarget> findJobTarget(UUID targetId) {
        return queryOptional(
                """
                        SELECT id, profile_id, knowledge_document_id, job_title, company, description,
                               source_uri, created_at, updated_at
                        FROM job_targets WHERE id = ?
                        """,
                targetId,
                this::mapJobTarget
        );
    }

    @Transactional
    public UUID saveAnalysis(UUID profileId,
                             UUID resumeVersionId,
                             UUID jobTargetId,
                             String summary,
                             int matchScore,
                             List<RequirementMatch> requirements,
                             List<SearchResult> sources,
                             List<ResumeChangeDraft> changes) {
        UUID changeSetId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update(
                """
                        INSERT INTO resume_change_sets(
                            id, profile_id, resume_version_id, job_target_id, status, summary,
                            match_score, requirement_matrix_json, sources_json, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, 'reviewing', ?, ?, ?, ?, ?, ?)
                        """,
                changeSetId, profileId, resumeVersionId, jobTargetId, summary, matchScore,
                writeJson(requirements), writeJson(sources), timestamp(now), timestamp(now)
        );
        for (int index = 0; index < changes.size(); index++) {
            ResumeChangeDraft change = changes.get(index);
            jdbcTemplate.update(
                    """
                            INSERT INTO resume_changes(
                                id, change_set_id, change_index, section, original_text, suggested_text,
                                reason, evidence_json, evidence_verified, verification_issues_json,
                                verification_version, status, created_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                            """,
                    UUID.randomUUID(), changeSetId, index + 1, change.section(), change.originalText(),
                    change.suggestedText(), change.reason(), writeJson(change.evidence()),
                    change.evidenceVerified(), writeJson(change.verificationIssues()),
                    change.evidenceVerified() ? "proposed" : "blocked", timestamp(now)
            );
        }
        return changeSetId;
    }

    public Optional<ResumeAnalysis> findAnalysis(UUID changeSetId) {
        List<ResumeChange> changes = listChanges(changeSetId);
        return jdbcTemplate.query(
                """
                        SELECT summary, match_score, requirement_matrix_json, sources_json, status
                        FROM resume_change_sets WHERE id = ?
                        """,
                ps -> ps.setObject(1, changeSetId),
                rs -> rs.next()
                        ? Optional.of(new ResumeAnalysis(
                                changeSetId,
                                rs.getString("summary"),
                                rs.getInt("match_score"),
                                readJson(rs.getString("requirement_matrix_json"), REQUIREMENTS_TYPE, List.of()),
                                changes,
                                readJson(rs.getString("sources_json"), SOURCES_TYPE, List.of()),
                                rs.getString("status")
                        ))
                        : Optional.empty()
        );
    }

    public List<ResumeAnalysisSummary> listAnalyses(UUID profileId) {
        return jdbcTemplate.query(
                """
                        SELECT cs.id, cs.resume_version_id, cs.job_target_id, cs.match_score, cs.status,
                               cs.created_at, rv.title AS resume_title, jt.job_title, jt.company
                        FROM resume_change_sets cs
                        JOIN resume_versions rv ON rv.id = cs.resume_version_id
                        JOIN job_targets jt ON jt.id = cs.job_target_id
                        WHERE cs.profile_id = ?
                        ORDER BY cs.created_at DESC
                        LIMIT 100
                        """,
                (rs, rowNum) -> new ResumeAnalysisSummary(
                        rs.getObject("id", UUID.class),
                        rs.getObject("resume_version_id", UUID.class),
                        rs.getObject("job_target_id", UUID.class),
                        rs.getString("resume_title"),
                        rs.getString("job_title"),
                        rs.getString("company"),
                        rs.getInt("match_score"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant()
                ),
                profileId
        );
    }

    public List<ResumeChange> listChanges(UUID changeSetId) {
        return jdbcTemplate.query(
                """
                        SELECT id, change_index, section, original_text, suggested_text, reason,
                               evidence_json, evidence_verified, verification_issues_json,
                               verification_version, status
                        FROM resume_changes WHERE change_set_id = ? ORDER BY change_index
                        """,
                (rs, rowNum) -> new ResumeChange(
                        rs.getObject("id", UUID.class),
                        rs.getInt("change_index"),
                        rs.getString("section"),
                        rs.getString("original_text"),
                        rs.getString("suggested_text"),
                        rs.getString("reason"),
                        readJson(rs.getString("evidence_json"), STRING_LIST_TYPE, List.of()),
                        rs.getBoolean("evidence_verified") && rs.getInt("verification_version") == 1,
                        rs.getInt("verification_version") == 1
                                ? readJson(rs.getString("verification_issues_json"), STRING_LIST_TYPE, List.of())
                                : List.of("历史分析尚未通过当前证据校验，请重新分析。"),
                        rs.getString("status")
                ),
                changeSetId
        );
    }

    @Transactional
    public ResumeChange updateChange(UUID changeId, String decision) {
        if (!"accepted".equalsIgnoreCase(decision) && !"rejected".equalsIgnoreCase(decision)) {
            throw new CareerReviewException("请选择接受或忽略。");
        }
        String normalized = decision.toLowerCase(java.util.Locale.ROOT);
        int updated = jdbcTemplate.update(
                """
                        UPDATE resume_changes AS rc SET status = ?
                        FROM resume_change_sets AS cs
                        WHERE rc.id = ? AND rc.change_set_id = cs.id AND cs.status = 'reviewing'
                          AND (CAST(? AS VARCHAR) = 'rejected'
                               OR (rc.evidence_verified = TRUE AND rc.verification_version = 1))
                        """,
                normalized, changeId, normalized
        );
        if (updated == 0) {
            throw new CareerReviewException("该建议缺少有效证据，或分析已生成版本，无法更改审核结果。");
        }
        return jdbcTemplate.queryForObject(
                """
                        SELECT id, change_index, section, original_text, suggested_text, reason,
                               evidence_json, evidence_verified, verification_issues_json,
                               verification_version, status
                        FROM resume_changes WHERE id = ?
                        """,
                (rs, rowNum) -> new ResumeChange(
                        rs.getObject("id", UUID.class), rs.getInt("change_index"), rs.getString("section"),
                        rs.getString("original_text"), rs.getString("suggested_text"), rs.getString("reason"),
                        readJson(rs.getString("evidence_json"), STRING_LIST_TYPE, List.of()),
                        rs.getBoolean("evidence_verified") && rs.getInt("verification_version") == 1,
                        rs.getInt("verification_version") == 1
                                ? readJson(rs.getString("verification_issues_json"), STRING_LIST_TYPE, List.of())
                                : List.of("历史分析尚未通过当前证据校验，请重新分析。"),
                        rs.getString("status")
                ),
                changeId
        );
    }

    public ChangeSetContext findChangeSetContext(UUID changeSetId) {
        return jdbcTemplate.queryForObject(
                "SELECT profile_id, resume_version_id, job_target_id FROM resume_change_sets WHERE id = ?",
                (rs, rowNum) -> new ChangeSetContext(
                        rs.getObject("profile_id", UUID.class),
                        rs.getObject("resume_version_id", UUID.class),
                        rs.getObject("job_target_id", UUID.class)
                ),
                changeSetId
        );
    }

    @Transactional
    public void completeChangeSet(UUID changeSetId) {
        jdbcTemplate.update("UPDATE resume_change_sets SET status = 'applied', updated_at = ? WHERE id = ?", timestamp(Instant.now()), changeSetId);
    }

    @Transactional
    public UUID createInterviewSession(UUID profileId,
                                       UUID resumeVersionId,
                                       UUID jobTargetId,
                                       String mode,
                                       int maxQuestions,
                                       List<InterviewQuestion> questions) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update(
                """
                        INSERT INTO interview_sessions(
                            id, profile_id, resume_version_id, job_target_id, mode, status,
                            plan_json, current_index, max_questions, started_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, 'active', ?, 0, ?, ?, ?)
                        """,
                id, profileId, resumeVersionId, jobTargetId, mode, writeJson(questions), maxQuestions, timestamp(now), timestamp(now)
        );
        return id;
    }

    public InterviewSessionContext findInterviewContext(UUID sessionId) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT id, profile_id, resume_version_id, job_target_id, mode, status,
                               plan_json, current_index, max_questions, final_report_json, started_at, completed_at
                        FROM interview_sessions WHERE id = ?
                        """,
                (rs, rowNum) -> new InterviewSessionContext(
                        rs.getObject("id", UUID.class),
                        rs.getObject("profile_id", UUID.class),
                        rs.getObject("resume_version_id", UUID.class),
                        rs.getObject("job_target_id", UUID.class),
                        rs.getString("mode"),
                        rs.getString("status"),
                        readJson(rs.getString("plan_json"), QUESTIONS_TYPE, List.of()),
                        rs.getInt("current_index"),
                        rs.getInt("max_questions"),
                        rs.getString("final_report_json"),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant()
                ),
                sessionId
        );
    }

    public List<InterviewSessionSummary> listInterviewSessions(UUID profileId) {
        return jdbcTemplate.query(
                """
                        SELECT s.id, s.resume_version_id, s.job_target_id, s.mode, s.status,
                               s.current_index, s.max_questions, s.started_at, s.completed_at,
                               rv.title AS resume_title, jt.job_title, jt.company
                        FROM interview_sessions s
                        JOIN resume_versions rv ON rv.id = s.resume_version_id
                        JOIN job_targets jt ON jt.id = s.job_target_id
                        WHERE s.profile_id = ?
                        ORDER BY s.updated_at DESC
                        LIMIT 100
                        """,
                (rs, rowNum) -> new InterviewSessionSummary(
                        rs.getObject("id", UUID.class),
                        rs.getObject("resume_version_id", UUID.class),
                        rs.getObject("job_target_id", UUID.class),
                        rs.getString("resume_title"),
                        rs.getString("job_title"),
                        rs.getString("company"),
                        rs.getString("mode"),
                        rs.getString("status"),
                        rs.getInt("current_index"),
                        rs.getInt("max_questions"),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant()
                ),
                profileId
        );
    }

    @Transactional
    public void saveInterviewTurn(UUID sessionId,
                                  int turnIndex,
                                  String question,
                                  String answer,
                                  InterviewFeedback feedback) {
        jdbcTemplate.update(
                """
                        INSERT INTO interview_turns(
                            id, session_id, turn_index, question, answer, feedback_json, score, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID(), sessionId, turnIndex, question, answer, writeJson(feedback), feedback.score(), timestamp(Instant.now())
        );
        jdbcTemplate.update(
                "UPDATE interview_sessions SET current_index = ?, updated_at = ? WHERE id = ?",
                turnIndex + 1, timestamp(Instant.now()), sessionId
        );
    }

    public List<InterviewTurn> listInterviewTurns(UUID sessionId, boolean includeFeedback) {
        return jdbcTemplate.query(
                """
                        SELECT turn_index, question, answer, feedback_json, created_at
                        FROM interview_turns WHERE session_id = ? ORDER BY turn_index
                        """,
                (rs, rowNum) -> new InterviewTurn(
                        rs.getInt("turn_index"),
                        rs.getString("question"),
                        rs.getString("answer"),
                        includeFeedback ? readJson(rs.getString("feedback_json"), InterviewFeedback.class, null) : null,
                        rs.getTimestamp("created_at").toInstant()
                ),
                sessionId
        );
    }

    @Transactional
    public void completeInterview(UUID sessionId, String finalReport) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                """
                        UPDATE interview_sessions
                        SET status = 'completed', final_report_json = ?, completed_at = ?, updated_at = ?
                        WHERE id = ?
                        """,
                finalReport, timestamp(now), timestamp(now), sessionId
        );
    }

    private void touchProfile(UUID profileId) {
        jdbcTemplate.update("UPDATE career_profiles SET updated_at = ? WHERE id = ?", timestamp(Instant.now()), profileId);
    }

    private CareerProfile mapProfile(ResultSet rs) throws SQLException {
        return new CareerProfile(
                rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getObject("knowledge_base_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()
        );
    }

    private ResumeVersion mapResume(ResultSet rs) throws SQLException {
        return new ResumeVersion(
                rs.getObject("id", UUID.class), rs.getObject("profile_id", UUID.class),
                rs.getObject("knowledge_document_id", UUID.class), rs.getObject("parent_version_id", UUID.class),
                rs.getInt("version_number"), rs.getString("title"), rs.getString("content"),
                rs.getString("source_type"), rs.getString("source_format"), rs.getTimestamp("created_at").toInstant()
        );
    }

    private JobTarget mapJobTarget(ResultSet rs) throws SQLException {
        return new JobTarget(
                rs.getObject("id", UUID.class), rs.getObject("profile_id", UUID.class),
                rs.getObject("knowledge_document_id", UUID.class), rs.getString("job_title"),
                rs.getString("company"), rs.getString("description"), rs.getString("source_uri"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()
        );
    }

    private <T> Optional<T> queryOptional(String sql, UUID id, SqlMapper<T> mapper) {
        return jdbcTemplate.query(
                sql,
                ps -> ps.setObject(1, id),
                rs -> rs.next() ? Optional.of(mapper.map(rs)) : Optional.empty()
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法序列化求职助手数据。", ex);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            return fallback;
        }
    }

    private <T> T readJson(String json, Class<T> type, T fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            return fallback;
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    @FunctionalInterface
    private interface SqlMapper<T> {
        T map(ResultSet resultSet) throws SQLException;
    }

    public record ResumeChangeDraft(
            String section,
            String originalText,
            String suggestedText,
            String reason,
            List<String> evidence,
            boolean evidenceVerified,
            List<String> verificationIssues
    ) {
    }

    public record ChangeSetContext(UUID profileId, UUID resumeVersionId, UUID jobTargetId) {
    }

    public record InterviewSessionContext(
            UUID id,
            UUID profileId,
            UUID resumeVersionId,
            UUID jobTargetId,
            String mode,
            String status,
            List<InterviewQuestion> questions,
            int currentIndex,
            int maxQuestions,
            String finalReport,
            Instant startedAt,
            Instant completedAt
    ) {
    }
}
