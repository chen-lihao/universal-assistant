package com.hao.universalassistantbackend.career;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CareerPrivacyServiceTests {

    private final CareerPrivacyService privacyService = new CareerPrivacyService();

    @Test
    void redactsContactAndIdentityDataButKeepsCareerEvidence() {
        String resume = """
                张三 13812345678 zhangsan@example.com
                身份证：440101199001011234
                现居住地：广东省广州市天河区示例路 1 号
                微信：resume_candidate
                负责 Spring Boot 服务开发，将接口响应时间降低 30%。
                """;

        String redacted = privacyService.redactForExternalProcessing(resume);

        assertThat(redacted)
                .doesNotContain("13812345678", "zhangsan@example.com", "440101199001011234", "示例路 1 号", "resume_candidate")
                .contains("[已隐藏手机号]", "[已隐藏邮箱]", "[已隐藏证件号]", "[已隐藏详细地址]", "[已隐藏账号]")
                .contains("Spring Boot", "降低 30%");
    }
}
