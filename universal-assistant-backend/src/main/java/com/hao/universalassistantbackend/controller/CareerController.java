package com.hao.universalassistantbackend.controller;

import com.hao.universalassistantbackend.career.CareerModels.AnalyzeResumeRequest;
import com.hao.universalassistantbackend.career.CareerModels.AnswerInterviewRequest;
import com.hao.universalassistantbackend.career.CareerModels.CareerProfile;
import com.hao.universalassistantbackend.career.CareerModels.CreateJobTargetRequest;
import com.hao.universalassistantbackend.career.CareerModels.CreateProfileRequest;
import com.hao.universalassistantbackend.career.CareerModels.ImportResumeRequest;
import com.hao.universalassistantbackend.career.CareerModels.JobTarget;
import com.hao.universalassistantbackend.career.CareerModels.ResumeVersion;
import com.hao.universalassistantbackend.career.CareerModels.StartInterviewRequest;
import com.hao.universalassistantbackend.career.CareerModels.UpdateResumeChangeRequest;
import com.hao.universalassistantbackend.career.CareerModels.UpdateProfileRequest;
import com.hao.universalassistantbackend.career.CareerService;
import com.hao.universalassistantbackend.career.CareerReviewException;
import com.hao.universalassistantbackend.career.InterviewService;
import com.hao.universalassistantbackend.career.ResumeFileParser;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/career")
public class CareerController {

    private final CareerService careerService;
    private final InterviewService interviewService;
    private final ResumeFileParser resumeFileParser;

    public CareerController(CareerService careerService, InterviewService interviewService,
                            ResumeFileParser resumeFileParser) {
        this.careerService = careerService;
        this.interviewService = interviewService;
        this.resumeFileParser = resumeFileParser;
    }

    @GetMapping("/profiles")
    public List<CareerProfile> listProfiles() {
        return careerService.listProfiles();
    }

    @PostMapping("/profiles")
    public ResponseEntity<?> createProfile(@RequestBody(required = false) CreateProfileRequest request) {
        return execute(() -> careerService.createProfile(request));
    }

    @PatchMapping("/profiles/{profileId}")
    public ResponseEntity<?> updateProfile(@PathVariable UUID profileId,
                                           @RequestBody UpdateProfileRequest request) {
        return execute(() -> careerService.updateProfile(profileId, request));
    }

    @DeleteMapping("/profiles/{profileId}")
    public ResponseEntity<?> deleteProfile(@PathVariable UUID profileId) {
        return execute(() -> careerService.deleteProfile(profileId));
    }

    @PostMapping("/resumes")
    public ResponseEntity<?> importResume(@RequestBody ImportResumeRequest request) {
        return execute(() -> careerService.importResume(request));
    }

    @PostMapping(value = "/resumes/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> parseResume(@RequestParam("file") MultipartFile file) {
        return execute(() -> resumeFileParser.parse(file));
    }

    @GetMapping("/profiles/{profileId}/resumes")
    public ResponseEntity<?> listResumes(@PathVariable UUID profileId) {
        return execute(() -> careerService.listResumes(profileId));
    }

    @PostMapping("/job-targets")
    public ResponseEntity<?> createJobTarget(@RequestBody CreateJobTargetRequest request) {
        return execute(() -> careerService.createJobTarget(request));
    }

    @GetMapping("/profiles/{profileId}/job-targets")
    public ResponseEntity<?> listJobTargets(@PathVariable UUID profileId) {
        return execute(() -> careerService.listJobTargets(profileId));
    }

    @PostMapping("/resume-analyses")
    public ResponseEntity<?> analyzeResume(@RequestBody AnalyzeResumeRequest request) {
        return execute(() -> careerService.analyzeResume(request));
    }

    @GetMapping("/resume-analyses/{changeSetId}")
    public ResponseEntity<?> getAnalysis(@PathVariable UUID changeSetId) {
        return execute(() -> careerService.getAnalysis(changeSetId));
    }

    @GetMapping("/profiles/{profileId}/resume-analyses")
    public ResponseEntity<?> listAnalyses(@PathVariable UUID profileId) {
        return execute(() -> careerService.listAnalyses(profileId));
    }

    @PatchMapping("/resume-changes/{changeId}")
    public ResponseEntity<?> updateChange(@PathVariable UUID changeId,
                                          @RequestBody UpdateResumeChangeRequest request) {
        return execute(() -> careerService.updateChange(changeId, request));
    }

    @PostMapping("/resume-analyses/{changeSetId}/apply")
    public ResponseEntity<?> applyChanges(@PathVariable UUID changeSetId) {
        return execute(() -> careerService.applyAcceptedChanges(changeSetId));
    }

    @PostMapping("/interviews")
    public ResponseEntity<?> startInterview(@RequestBody StartInterviewRequest request) {
        return execute(() -> interviewService.start(request));
    }

    @GetMapping("/interviews/{sessionId}")
    public ResponseEntity<?> getInterview(@PathVariable UUID sessionId) {
        return execute(() -> interviewService.get(sessionId));
    }

    @GetMapping("/profiles/{profileId}/interviews")
    public ResponseEntity<?> listInterviews(@PathVariable UUID profileId) {
        return execute(() -> interviewService.list(profileId));
    }

    @PostMapping("/interviews/{sessionId}/answers")
    public ResponseEntity<?> answerInterview(@PathVariable UUID sessionId,
                                             @RequestBody AnswerInterviewRequest request) {
        return execute(() -> interviewService.answer(sessionId, request));
    }

    private ResponseEntity<?> execute(Supplier<?> action) {
        try {
            return ResponseEntity.ok(action.get());
        } catch (IllegalArgumentException | IllegalStateException | CareerReviewException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }
}
