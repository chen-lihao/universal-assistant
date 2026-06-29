package com.hao.universalassistantbackend.repository;

import com.hao.universalassistantbackend.entity.ConversationSummaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ConversationSummaryRepository extends JpaRepository<ConversationSummaryEntity, UUID> {

    Optional<ConversationSummaryEntity> findByConversation_Id(UUID conversationId);
}
