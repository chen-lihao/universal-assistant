package com.hao.universalassistantbackend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent_runs")
public class AgentRunEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private ConversationEntity conversation;

    @Column(name = "user_message_id")
    private UUID userMessageId;

    @Column(name = "assistant_message_id")
    private UUID assistantMessageId;

    @Column(nullable = false, length = 40)
    private String mode;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(name = "user_goal", nullable = false, columnDefinition = "text")
    private String userGoal;

    @Column(name = "final_answer", columnDefinition = "text")
    private String finalAnswer;

    @Column(name = "partial_answer", columnDefinition = "text")
    private String partialAnswer;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(length = 80)
    private String model;

    @Column(name = "realtime_search_used")
    private Boolean realtimeSearchUsed;

    @Column(name = "model_available")
    private Boolean modelAvailable;

    @Column(name = "sources_json", columnDefinition = "text")
    private String sourcesJson;

    @Column(name = "max_steps", nullable = false)
    private int maxSteps;

    @Column(name = "reflection_limit", nullable = false)
    private int reflectionLimit;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "invalidated_at")
    private Instant invalidatedAt;

    @Column(name = "pending_tool_name", length = 80)
    private String pendingToolName;

    @Column(name = "pending_tool_input_json", columnDefinition = "text")
    private String pendingToolInputJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (startedAt == null) {
            startedAt = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public ConversationEntity getConversation() {
        return conversation;
    }

    public void setConversation(ConversationEntity conversation) {
        this.conversation = conversation;
    }

    public UUID getUserMessageId() {
        return userMessageId;
    }

    public void setUserMessageId(UUID userMessageId) {
        this.userMessageId = userMessageId;
    }

    public UUID getAssistantMessageId() {
        return assistantMessageId;
    }

    public void setAssistantMessageId(UUID assistantMessageId) {
        this.assistantMessageId = assistantMessageId;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getUserGoal() {
        return userGoal;
    }

    public void setUserGoal(String userGoal) {
        this.userGoal = userGoal;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public void setFinalAnswer(String finalAnswer) {
        this.finalAnswer = finalAnswer;
    }

    public String getPartialAnswer() {
        return partialAnswer;
    }

    public void setPartialAnswer(String partialAnswer) {
        this.partialAnswer = partialAnswer;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Boolean getRealtimeSearchUsed() {
        return realtimeSearchUsed;
    }

    public void setRealtimeSearchUsed(Boolean realtimeSearchUsed) {
        this.realtimeSearchUsed = realtimeSearchUsed;
    }

    public Boolean getModelAvailable() {
        return modelAvailable;
    }

    public void setModelAvailable(Boolean modelAvailable) {
        this.modelAvailable = modelAvailable;
    }

    public String getSourcesJson() {
        return sourcesJson;
    }

    public void setSourcesJson(String sourcesJson) {
        this.sourcesJson = sourcesJson;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public int getReflectionLimit() {
        return reflectionLimit;
    }

    public void setReflectionLimit(int reflectionLimit) {
        this.reflectionLimit = reflectionLimit;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Instant getInvalidatedAt() {
        return invalidatedAt;
    }

    public void setInvalidatedAt(Instant invalidatedAt) {
        this.invalidatedAt = invalidatedAt;
    }

    public String getPendingToolName() {
        return pendingToolName;
    }

    public void setPendingToolName(String pendingToolName) {
        this.pendingToolName = pendingToolName;
    }

    public String getPendingToolInputJson() {
        return pendingToolInputJson;
    }

    public void setPendingToolInputJson(String pendingToolInputJson) {
        this.pendingToolInputJson = pendingToolInputJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
