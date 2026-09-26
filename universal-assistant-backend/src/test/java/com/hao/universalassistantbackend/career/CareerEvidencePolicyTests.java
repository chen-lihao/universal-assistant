package com.hao.universalassistantbackend.career;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CareerEvidencePolicyTests {

    private static final String RESUME = "负责 Spring Boot 服务开发，使用 PostgreSQL 和 Redis。主导接口性能优化，从 800ms 降低到 300ms。";

    @Test
    void acceptsClaimsAlreadyPresentInResume() {
        assertThat(CareerEvidencePolicy.claimsGrounded(
                "负责 Spring Boot 服务开发，主导接口性能优化，从 800ms 降低到 300ms。",
                RESUME
        )).isTrue();
    }

    @Test
    void rejectsNewTechnologyAndResponsibilityClaims() {
        assertThat(CareerEvidencePolicy.claimsGrounded(
                "负责 Spring Boot 微服务开发，使用 Redis 分布式锁。",
                RESUME
        )).isFalse();
        assertThat(CareerEvidencePolicy.claimsGrounded(
                "通过 SQL 调优将响应时间降低到 300ms。",
                RESUME
        )).isFalse();
    }

    @Test
    void locatesOriginalDespitePdfLineBreaks() {
        String corpus = "负责 Spring Boot 服务开发，\n使用 PostgreSQL 和 Redis。";
        assertThat(CareerEvidencePolicy.locateOriginal(
                "负责 Spring Boot 服务开发，使用 PostgreSQL 和 Redis。", corpus
        )).isEqualTo(corpus);
    }

    @Test
    void explainsUnverifiedClaims() {
        assertThat(CareerEvidencePolicy.claimIssues("主导 Kubernetes 部署，提升 50% 性能。", RESUME))
                .anyMatch(issue -> issue.contains("Kubernetes"))
                .anyMatch(issue -> issue.contains("50%"));
    }
}
