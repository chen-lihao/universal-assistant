package com.hao.universalassistantbackend.repository;

import com.hao.universalassistantbackend.entity.PendingAgentActionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PendingAgentActionRepository extends JpaRepository<PendingAgentActionEntity, UUID> {

    List<PendingAgentActionEntity> findByConversation_IdAndStatusOrderByCreatedAtDesc(UUID conversationId, String status);
}
