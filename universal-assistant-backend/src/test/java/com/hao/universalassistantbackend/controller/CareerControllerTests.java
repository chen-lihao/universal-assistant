package com.hao.universalassistantbackend.controller;

import com.hao.universalassistantbackend.career.CareerModels.CareerProfile;
import com.hao.universalassistantbackend.career.CareerModels.ParsedResume;
import com.hao.universalassistantbackend.career.CareerService;
import com.hao.universalassistantbackend.career.CareerReviewException;
import com.hao.universalassistantbackend.career.InterviewService;
import com.hao.universalassistantbackend.career.ResumeFileParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CareerControllerTests {

    @Mock
    private CareerService careerService;

    @Mock
    private InterviewService interviewService;

    @Mock
    private ResumeFileParser resumeFileParser;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new CareerController(careerService, interviewService, resumeFileParser)).build();
    }

    @Test
    void exposesProfileHistoryAndLifecycleEndpoints() throws Exception {
        UUID profileId = UUID.randomUUID();
        CareerProfile profile = profile(profileId, "Java 求职档案");
        when(careerService.listProfiles()).thenReturn(List.of(profile));
        when(careerService.updateProfile(any(), any())).thenReturn(profile(profileId, "后端求职档案"));
        when(careerService.deleteProfile(profileId)).thenReturn(profile);
        when(careerService.listAnalyses(profileId)).thenReturn(List.of());
        when(interviewService.list(profileId)).thenReturn(List.of());

        mockMvc.perform(get("/api/career/profiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(profileId.toString()));

        mockMvc.perform(patch("/api/career/profiles/{profileId}", profileId)
                        .contentType("application/json")
                        .content("{\"name\":\"后端求职档案\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("后端求职档案"));

        mockMvc.perform(get("/api/career/profiles/{profileId}/resume-analyses", profileId))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        mockMvc.perform(get("/api/career/profiles/{profileId}/interviews", profileId))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        mockMvc.perform(delete("/api/career/profiles/{profileId}", profileId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(profileId.toString()));
    }

    @Test
    void mapsDomainValidationFailuresToBadRequest() throws Exception {
        UUID profileId = UUID.randomUUID();
        when(careerService.deleteProfile(profileId)).thenThrow(new IllegalArgumentException("求职档案不存在。"));

        mockMvc.perform(delete("/api/career/profiles/{profileId}", profileId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("求职档案不存在。"));
    }

    @Test
    void previewsMultipartResumeWithoutImportingIt() throws Exception {
        var file = new MockMultipartFile("file", "resume.md", "text/markdown", "# Resume".getBytes());
        when(resumeFileParser.parse(any())).thenReturn(new ParsedResume("resume.md", "md", "# Resume", List.of()));

        mockMvc.perform(multipart("/api/career/resumes/parse").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("md"))
                .andExpect(jsonPath("$.content").value("# Resume"));
    }

    @Test
    void returnsReadableReviewError() throws Exception {
        UUID changeId = UUID.randomUUID();
        when(careerService.updateChange(any(), any())).thenThrow(new CareerReviewException("该建议缺少有效证据。"));

        mockMvc.perform(patch("/api/career/resume-changes/{changeId}", changeId)
                        .contentType("application/json")
                        .content("{\"decision\":\"accepted\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("该建议缺少有效证据。"));
    }

    private CareerProfile profile(UUID id, String name) {
        Instant now = Instant.parse("2026-09-24T00:00:00Z");
        return new CareerProfile(id, name, UUID.randomUUID(), now, now);
    }
}
