package com.hao.universalassistantbackend.career;

import com.hao.universalassistantbackend.career.CareerModels.CareerProfile;
import com.hao.universalassistantbackend.career.CareerModels.CreateProfileRequest;
import com.hao.universalassistantbackend.career.CareerModels.CreateJobTargetRequest;
import com.hao.universalassistantbackend.career.CareerModels.ImportResumeRequest;
import com.hao.universalassistantbackend.career.CareerModels.ResumeVersion;
import com.hao.universalassistantbackend.career.CareerModels.UpdateProfileRequest;
import com.hao.universalassistantbackend.rag.KnowledgeService;
import com.hao.universalassistantbackend.rag.KnowledgeDocumentRequest;
import com.hao.universalassistantbackend.rag.KnowledgeDocumentResponse;
import com.hao.universalassistantbackend.rag.KnowledgeHit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.flyway.enabled=true",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.ai.dashscope.api-key=missing-api-key",
                "assistant.rag.enabled=true"
        }
)
class CareerPostgresIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
    );

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CareerService careerService;

    @Autowired
    private CareerRepository careerRepository;

    @Autowired
    private KnowledgeService knowledgeService;

    @Test
    void keepsResumeKnowledgeOutOfGeneralSearch() {
        CareerProfile profile = careerService.createProfile(new CreateProfileRequest("Scoped evidence"));
        String uniqueTerm = "profiletoken" + UUID.randomUUID().toString().replace("-", "");
        careerService.importResume(new ImportResumeRequest(profile.id(), "Private resume", uniqueTerm + " Java 服务开发", "md"));

        assertThat(knowledgeService.search(uniqueTerm, profile.knowledgeBaseId()))
                .anyMatch(hit -> hit.content().contains(uniqueTerm));
        assertThat(knowledgeService.search(uniqueTerm, KnowledgeService.DEFAULT_KNOWLEDGE_BASE_ID)).isEmpty();
        assertThatThrownBy(() -> knowledgeService.search(uniqueTerm, null))
                .isInstanceOf(IllegalArgumentException.class);

        KnowledgeDocumentResponse[] generalDocuments = restTemplate.getForObject(
                "/api/knowledge/documents", KnowledgeDocumentResponse[].class
        );
        assertThat(generalDocuments).isNotNull().noneMatch(document -> "Private resume".equals(document.title()));
        KnowledgeHit[] generalHits = restTemplate.getForObject(
                "/api/knowledge/search?q=" + uniqueTerm, KnowledgeHit[].class
        );
        assertThat(generalHits).isEmpty();

        UUID resumeDocumentId = jdbcTemplate.queryForObject(
                "SELECT id FROM knowledge_documents WHERE knowledge_base_id = ? AND title = ?",
                UUID.class, profile.knowledgeBaseId(), "Private resume"
        );
        assertThat(restTemplate.getForEntity(
                "/api/knowledge/documents/{documentId}", String.class, resumeDocumentId
        ).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.postForEntity(
                "/api/knowledge/documents",
                new KnowledgeDocumentRequest(profile.knowledgeBaseId().toString(), "Injected", null, "private", null),
                String.class
        ).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void migratesSchemaAndCascadesCareerProfileData() {
        CareerProfile profile = restTemplate.postForObject(
                "/api/career/profiles",
                new CreateProfileRequest("Integration profile"),
                CareerProfile.class
        );
        assertThat(profile).isNotNull();

        ResponseEntity<ResumeVersion> resumeResponse = restTemplate.postForEntity(
                "/api/career/resumes",
                new ImportResumeRequest(profile.id(), "Backend resume", "Java and PostgreSQL experience"),
                ResumeVersion.class
        );
        assertThat(resumeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResumeVersion resume = resumeResponse.getBody();
        assertThat(resume).isNotNull();
        assertThat(resume.profileId()).isEqualTo(profile.id());
        assertThat(resume.sourceFormat()).isEqualTo("text");

        CareerProfile renamed = careerService.updateProfile(profile.id(), new UpdateProfileRequest("Renamed profile"));
        assertThat(renamed.name()).isEqualTo("Renamed profile");

        restTemplate.delete("/api/career/profiles/{profileId}", profile.id());

        Integer profileCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM career_profiles WHERE id = ?",
                Integer.class,
                profile.id()
        );
        Integer resumeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resume_versions WHERE profile_id = ?",
                Integer.class,
                profile.id()
        );
        Integer knowledgeBaseCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_bases WHERE id = ?",
                Integer.class,
                profile.knowledgeBaseId()
        );
        assertThat(profileCount).isZero();
        assertThat(resumeCount).isZero();
        assertThat(knowledgeBaseCount).isZero();
    }

    @Test
    void acceptsVerifiedChangeButRejectsUnsupportedOne() {
        CareerProfile profile = careerService.createProfile(new CreateProfileRequest("Evidence review"));
        ResumeVersion resume = careerService.importResume(new ImportResumeRequest(
                profile.id(), "Resume", "负责 Spring Boot 服务开发。", "md"
        ));
        assertThat(resume.sourceFormat()).isEqualTo("md");
        var target = careerService.createJobTarget(new CreateJobTargetRequest(
                profile.id(), "Backend Engineer", "Example", "Spring Boot development", null
        ));
        UUID changeSetId = careerRepository.saveAnalysis(
                profile.id(), resume.id(), target.id(), "Test review", 75, List.of(), List.of(),
                List.of(
                        new CareerRepository.ResumeChangeDraft("经历", "负责 Spring Boot 服务开发。",
                                "负责 Spring Boot 服务开发与维护。", "结构更清楚", List.of("负责 Spring Boot 服务开发。"), true, List.of()),
                        new CareerRepository.ResumeChangeDraft("经历", "负责 Spring Boot 服务开发。",
                                "主导 Kubernetes 架构。", "缺少证据", List.of(), false, List.of("新增未证实技术"))
                )
        );
        var changes = careerRepository.listChanges(changeSetId);
        assertThat(careerRepository.updateChange(changes.get(0).id(), "accepted").status()).isEqualTo("accepted");
        assertThatThrownBy(() -> careerRepository.updateChange(changes.get(1).id(), "accepted"))
                .isInstanceOf(CareerReviewException.class)
                .hasMessageContaining("缺少有效证据");
        assertThat(careerRepository.updateChange(changes.get(1).id(), "rejected").status()).isEqualTo("rejected");

        jdbcTemplate.update("UPDATE resume_changes SET verification_version = 0 WHERE id = ?", changes.get(0).id());
        assertThat(careerRepository.listChanges(changeSetId).get(0).evidenceVerified()).isFalse();
        assertThat(careerRepository.listChanges(changeSetId).get(0).verificationIssues())
                .contains("历史分析尚未通过当前证据校验，请重新分析。");
        assertThatThrownBy(() -> careerService.applyAcceptedChanges(changeSetId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("有证据的修改");
    }
}
