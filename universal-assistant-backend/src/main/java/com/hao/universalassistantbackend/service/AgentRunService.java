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
import com.hao.universalassistantbackend.model.SearchResult;
import com.hao.universalassistantbackend.repository.AgentRunRepository;
import com.hao.universalassistantbackend.repository.AgentStepRepository;
import com.hao.universalassistantbackend.repository.ToolCallRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class AgentRunService {

    private final AgentRunRepository agentRunRepository;
    private final AgentStepRepository agentStepRepository;
    private final ToolCallRepository toolCallRepository;
    private final ObjectMapper objectMapper;
    private final int defaultMaxSteps;
    private final int defaultReflectionLimit;
    private final ConcurrentMap<UUID, Boolean> cancellationRequests = new ConcurrentHashMap<>();

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
        run.setPartialAnswer("");
        return new AgentRunContext(agentRunRepository.save(run), mode);
    }

    @Transactional(readOnly = true)
    public Optional<AgentRunEntity> findRun(UUID runId) {
        return agentRunRepository.findByIdWithConversation(runId);
    }

    @Transactional(readOnly = true)
    public AgentRunContext contextForRun(AgentRunEntity run) {
        AgentRunContext context = new AgentRunContext(run, parseMode(run.getMode()));
        agentStepRepository.findByRun_IdOrderByStepIndexAsc(run.getId()).stream()
                .map(this::toStepResponse)
                .forEach(context::addStep);
        return context;
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
    public void attachAssistantMessage(AgentRunContext context, MessageEntity assistantMessage) {
        if (context == null || assistantMessage == null) {
            return;
        }

        AgentRunEntity run = agentRunRepository.findById(context.run().getId()).orElse(context.run());
        run.setAssistantMessageId(assistantMessage.getId());
        agentRunRepository.save(run);
        context.run().setAssistantMessageId(assistantMessage.getId());
    }

    @Transactional
    public void updateProgress(AgentRunContext context,
                               String partialAnswer,
                               Boolean realtimeSearchUsed,
                               Boolean modelAvailable,
                               List<SearchResult> sources) {
        if (context == null) {
            return;
        }

        AgentRunEntity run = agentRunRepository.findById(context.run().getId()).orElse(context.run());
        if ("completed".equals(run.getStatus()) || "cancelled".equals(run.getStatus()) || "invalidated".equals(run.getStatus())) {
            return;
        }
        if (partialAnswer != null) {
            run.setPartialAnswer(partialAnswer);
        }
        if (realtimeSearchUsed != null) {
            run.setRealtimeSearchUsed(realtimeSearchUsed);
        }
        if (modelAvailable != null) {
            run.setModelAvailable(modelAvailable);
        }
        if (sources != null) {
            run.setSourcesJson(writeSources(sources));
        }
        AgentRunEntity saved = agentRunRepository.save(run);
        copyRunProgress(saved, context.run());
    }

    @Transactional
    public void completeRun(AgentRunContext context, MessageEntity assistantMessage, String finalAnswer) {
        AgentRunEntity run = agentRunRepository.findById(context.run().getId()).orElse(context.run());
        if ("cancelled".equals(run.getStatus()) || "invalidated".equals(run.getStatus()) || isCancelRequested(run.getId())) {
            return;
        }
        run.setAssistantMessageId(assistantMessage.getId());
        run.setFinalAnswer(finalAnswer);
        run.setPartialAnswer(null);
        run.setErrorMessage(null);
        run.setStatus("completed");
        run.setCompletedAt(Instant.now());
        run.setPendingToolName(null);
        run.setPendingToolInputJson(null);
        AgentRunEntity saved = agentRunRepository.save(run);
        copyRunProgress(saved, context.run());
    }

    @Transactional
    public void failRun(AgentRunContext context, String error) {
        failRun(context, error, null);
    }

    @Transactional
    public void failRun(AgentRunContext context, String error, String partialAnswer) {
        AgentRunEntity run = agentRunRepository.findById(context.run().getId()).orElse(context.run());
        if ("cancelled".equals(run.getStatus()) || "invalidated".equals(run.getStatus()) || isCancelRequested(run.getId())) {
            return;
        }
        run.setFinalAnswer(error);
        if (partialAnswer != null) {
            run.setPartialAnswer(partialAnswer);
        }
        run.setErrorMessage(error);
        run.setStatus("failed");
        run.setCompletedAt(Instant.now());
        AgentRunEntity saved = agentRunRepository.save(run);
        copyRunProgress(saved, context.run());
    }

    @Transactional
    public void markWaitingForTool(AgentRunContext context, String toolName, Map<String, ?> toolInput) {
        AgentRunEntity run = agentRunRepository.findById(context.run().getId()).orElse(context.run());
        run.setStatus("waiting_confirmation");
        run.setPendingToolName(toolName);
        run.setPendingToolInputJson(writeJson(toolInput));
        agentRunRepository.save(run);
        context.run().setStatus("waiting_confirmation");
        context.run().setPendingToolName(toolName);
        context.run().setPendingToolInputJson(run.getPendingToolInputJson());
    }

    @Transactional
    public void markRunning(AgentRunContext context) {
        AgentRunEntity run = agentRunRepository.findById(context.run().getId()).orElse(context.run());
        run.setStatus("running");
        run.setPendingToolName(null);
        run.setPendingToolInputJson(null);
        agentRunRepository.save(run);
        context.run().setStatus("running");
        context.run().setPendingToolName(null);
        context.run().setPendingToolInputJson(null);
    }

    @Transactional
    public boolean cancelRun(AgentRunEntity run, String partialAnswer) {
        AgentRunEntity entity = agentRunRepository.findById(run.getId()).orElse(run);
        if ("cancelled".equals(entity.getStatus()) || "completed".equals(entity.getStatus()) || "invalidated".equals(entity.getStatus())) {
            return false;
        }
        entity.setFinalAnswer(partialAnswer);
        entity.setPartialAnswer(partialAnswer);
        entity.setStatus("cancelled");
        entity.setCompletedAt(Instant.now());
        entity.setPendingToolName(null);
        entity.setPendingToolInputJson(null);
        AgentRunEntity saved = agentRunRepository.save(entity);
        copyRunProgress(saved, run);
        cancellationRequests.remove(entity.getId());
        return true;
    }

    public void requestCancel(UUID runId) {
        if (runId != null) {
            cancellationRequests.put(runId, true);
        }
    }

    public boolean isCancelRequested(UUID runId) {
        return runId != null && cancellationRequests.containsKey(runId);
    }

    @Transactional(readOnly = true)
    public boolean isCancelled(UUID runId) {
        return runId != null
                && agentRunRepository.findById(runId)
                .map(run -> "cancelled".equals(run.getStatus()))
                .orElse(false);
    }

    public boolean shouldStop(UUID runId) {
        return isCancelRequested(runId) || isCancelled(runId);
    }

    @Transactional
    public void invalidateAfterOrForUserMessage(UUID conversationId, UUID messageId, Instant createdAt) {
        agentRunRepository.invalidateAfterOrForUserMessage(conversationId, messageId, createdAt, Instant.now());
    }

    @Transactional(readOnly = true)
    public Map<UUID, AgentRunContext> contextsByAssistantMessageIds(Collection<UUID> assistantMessageIds) {
        if (assistantMessageIds == null || assistantMessageIds.isEmpty()) {
            return Map.of();
        }

        List<AgentRunEntity> runs = agentRunRepository.findByAssistantMessageIdIn(assistantMessageIds);
        if (runs.isEmpty()) {
            return Map.of();
        }

        Map<UUID, AgentRunContext> contextsByRunId = new LinkedHashMap<>();
        for (AgentRunEntity run : runs) {
            if (run.getInvalidatedAt() == null && run.getAssistantMessageId() != null) {
                contextsByRunId.put(run.getId(), new AgentRunContext(run, parseMode(run.getMode())));
            }
        }

        List<AgentStepEntity> steps = agentStepRepository.findByRun_IdInOrderByRun_IdAscStepIndexAsc(contextsByRunId.keySet());
        for (AgentStepEntity step : steps) {
            AgentRunContext context = contextsByRunId.get(step.getRun().getId());
            if (context != null) {
                context.addStep(toStepResponse(step));
            }
        }

        Map<UUID, AgentRunContext> result = new LinkedHashMap<>();
        for (AgentRunContext context : contextsByRunId.values()) {
            result.put(context.run().getAssistantMessageId(), context);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<AgentRunContext> unresolvedContexts(UUID conversationId) {
        List<AgentRunEntity> runs = agentRunRepository.findByConversation_IdAndInvalidatedAtIsNullAndAssistantMessageIdIsNullOrderByCreatedAtAsc(conversationId);
        if (runs.isEmpty()) {
            return List.of();
        }

        Map<UUID, AgentRunContext> contexts = new LinkedHashMap<>();
        for (AgentRunEntity run : runs) {
            contexts.put(run.getId(), new AgentRunContext(run, parseMode(run.getMode())));
        }

        List<AgentStepEntity> steps = agentStepRepository.findByRun_IdInOrderByRun_IdAscStepIndexAsc(contexts.keySet());
        for (AgentStepEntity step : steps) {
            AgentRunContext context = contexts.get(step.getRun().getId());
            if (context != null) {
                context.addStep(toStepResponse(step));
            }
        }
        return new ArrayList<>(contexts.values());
    }

    public Map<String, Object> readPendingToolInput(AgentRunEntity run) {
        if (!StringUtils.hasText(run.getPendingToolInputJson())) {
            return Map.of();
        }

        try {
            return objectMapper.readValue(run.getPendingToolInputJson(), new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
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

    private AgentMode parseMode(String mode) {
        try {
            return AgentMode.valueOf(mode);
        } catch (RuntimeException ex) {
            return AgentMode.DIRECT;
        }
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

    private String writeSources(List<SearchResult> sources) {
        if (sources == null || sources.isEmpty()) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(sources);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    private void copyRunProgress(AgentRunEntity source, AgentRunEntity target) {
        target.setAssistantMessageId(source.getAssistantMessageId());
        target.setStatus(source.getStatus());
        target.setFinalAnswer(source.getFinalAnswer());
        target.setPartialAnswer(source.getPartialAnswer());
        target.setErrorMessage(source.getErrorMessage());
        target.setRealtimeSearchUsed(source.getRealtimeSearchUsed());
        target.setModelAvailable(source.getModelAvailable());
        target.setSourcesJson(source.getSourcesJson());
        target.setPendingToolName(source.getPendingToolName());
        target.setPendingToolInputJson(source.getPendingToolInputJson());
        target.setCompletedAt(source.getCompletedAt());
        target.setInvalidatedAt(source.getInvalidatedAt());
    }
}
