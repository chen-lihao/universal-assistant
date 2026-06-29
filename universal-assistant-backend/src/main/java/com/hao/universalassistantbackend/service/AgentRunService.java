package com.hao.universalassistantbackend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hao.universalassistantbackend.entity.AgentRunEntity;
import com.hao.universalassistantbackend.entity.AgentStepEntity;
import com.hao.universalassistantbackend.entity.ConversationEntity;
import com.hao.universalassistantbackend.entity.MessageEntity;
import com.hao.universalassistantbackend.entity.ToolCallEntity;
import com.hao.universalassistantbackend.model.AgentMode;
import com.hao.universalassistantbackend.model.AgentRunContext;
import com.hao.universalassistantbackend.model.AgentStepResponse;
import com.hao.universalassistantbackend.repository.AgentRunRepository;
import com.hao.universalassistantbackend.repository.AgentStepRepository;
import com.hao.universalassistantbackend.repository.ToolCallRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Service
public class AgentRunService {

    private final AgentRunRepository agentRunRepository;
    private final AgentStepRepository agentStepRepository;
    private final ToolCallRepository toolCallRepository;
    private final ObjectMapper objectMapper;
    private final int defaultMaxSteps;
    private final int defaultReflectionLimit;

    public AgentRunService(AgentRunRepository agentRunRepository,
                           AgentStepRepository agentStepRepository,
                           ToolCallRepository toolCallRepository,
                           ObjectMapper objectMapper,
                           @Value("${assistant.agent.max-steps:6}") int defaultMaxSteps,
                           @Value("${assistant.agent.reflection-limit:1}") int defaultReflectionLimit) {
        this.agentRunRepository = agentRunRepository;
        this.agentStepRepository = agentStepRepository;
        this.toolCallRepository = toolCallRepository;
        this.objectMapper = objectMapper;
        this.defaultMaxSteps = Math.max(1, defaultMaxSteps);
        this.defaultReflectionLimit = Math.max(0, Math.min(2, defaultReflectionLimit));
    }

    @Transactional
    public AgentRunContext startRun(ConversationEntity conversation,
                                    MessageEntity userMessage,
                                    AgentMode mode,
                                    String userGoal,
                                    String model) {
        AgentRunEntity run = new AgentRunEntity();
        run.setConversation(conversation);
        run.setUserMessageId(userMessage.getId());
        run.setMode(mode.name());
        run.setStatus("running");
        run.setUserGoal(StringUtils.hasText(userGoal) ? userGoal : "");
        run.setModel(model);
        run.setMaxSteps(defaultMaxSteps);
        run.setReflectionLimit(defaultReflectionLimit);
        return new AgentRunContext(agentRunRepository.save(run), mode);
    }

    @Transactional
    public AgentStepResponse addStep(AgentRunContext context,
                                     String stepType,
                                     String title,
                                     String content,
                                     String status) {
        long stepCount = agentStepRepository.countByRun_Id(context.run().getId());
        AgentStepEntity step = new AgentStepEntity();
        step.setRun(agentRunRepository.getReferenceById(context.run().getId()));
        step.setStepIndex((int) stepCount + 1);
        step.setStepType(stepType);
        step.setTitle(StringUtils.hasText(title) ? title : stepType);
        step.setContent(content);
        step.setStatus(StringUtils.hasText(status) ? status : "completed");
        AgentStepResponse response = toStepResponse(agentStepRepository.save(step));
        context.addStep(response);
        return response;
    }

    @Transactional
    public void recordToolCall(AgentRunContext context,
                               AgentStepResponse step,
                               String toolName,
                               Map<String, ?> input,
                               String output,
                               String status,
                               Instant startedAt) {
        ToolCallEntity toolCall = new ToolCallEntity();
        toolCall.setRun(agentRunRepository.getReferenceById(context.run().getId()));
        if (step != null) {
            toolCall.setStep(agentStepRepository.getReferenceById(step.id()));
        }
        toolCall.setToolName(toolName);
        toolCall.setInputJson(writeJson(input));
        toolCall.setOutputText(output);
        toolCall.setStatus(StringUtils.hasText(status) ? status : "completed");
        if (startedAt != null) {
            toolCall.setDurationMs(Duration.between(startedAt, Instant.now()).toMillis());
        }
        toolCallRepository.save(toolCall);
    }

    @Transactional
    public void completeRun(AgentRunContext context, MessageEntity assistantMessage, String finalAnswer) {
        AgentRunEntity run = context.run();
        run.setAssistantMessageId(assistantMessage.getId());
        run.setFinalAnswer(finalAnswer);
        run.setStatus("completed");
        run.setCompletedAt(Instant.now());
        agentRunRepository.save(run);
    }

    @Transactional
    public void failRun(AgentRunContext context, String error) {
        AgentRunEntity run = context.run();
        run.setFinalAnswer(error);
        run.setStatus("failed");
        run.setCompletedAt(Instant.now());
        agentRunRepository.save(run);
    }

    public int defaultMaxSteps() {
        return defaultMaxSteps;
    }

    public int defaultReflectionLimit() {
        return defaultReflectionLimit;
    }

    private AgentStepResponse toStepResponse(AgentStepEntity step) {
        return new AgentStepResponse(
                step.getId(),
                step.getRun().getId(),
                step.getStepIndex(),
                step.getStepType(),
                step.getTitle(),
                step.getContent(),
                step.getStatus(),
                step.getCreatedAt()
        );
    }

    private String writeJson(Map<String, ?> input) {
        if (input == null || input.isEmpty()) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException ex) {
            return input.toString();
        }
    }
}
