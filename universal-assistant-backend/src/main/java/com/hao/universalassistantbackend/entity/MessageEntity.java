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
@Table(name = "messages")
public class MessageEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private ConversationEntity conversation;

    @Column(nullable = false, length = 24)
    private String role;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(length = 80)
    private String model;

    @Column(name = "realtime_search_used")
    private Boolean realtimeSearchUsed;

    @Column(name = "model_available")
    private Boolean modelAvailable;

    @Column(name = "sources_json", columnDefinition = "text")
    private String sourcesJson;

    @Column(name = "content_blocks_json", nullable = false, columnDefinition = "text")
    private String contentBlocksJson = "[]";

    @Column(nullable = false, length = 24)
    private String status = "completed";

    @Column(nullable = false)
    private int revision = 1;

    @Column(name = "edited_at")
    private Instant editedAt;

    @Column(name = "invalidated_at")
    private Instant invalidatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = "completed";
        }
        if (revision <= 0) {
            revision = 1;
        }
        if (contentBlocksJson == null) {
            contentBlocksJson = "[]";
        }
    }

    @PreUpdate
    void preUpdate() {
        if (status == null) {
            status = "completed";
        }
        if (revision <= 0) {
            revision = 1;
        }
        if (contentBlocksJson == null) {
            contentBlocksJson = "[]";
        }
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

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
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

    public String getContentBlocksJson() {
        return contentBlocksJson;
    }

    public void setContentBlocksJson(String contentBlocksJson) {
        this.contentBlocksJson = contentBlocksJson;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    public Instant getEditedAt() {
        return editedAt;
    }

    public void setEditedAt(Instant editedAt) {
        this.editedAt = editedAt;
    }

    public Instant getInvalidatedAt() {
        return invalidatedAt;
    }

    public void setInvalidatedAt(Instant invalidatedAt) {
        this.invalidatedAt = invalidatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
