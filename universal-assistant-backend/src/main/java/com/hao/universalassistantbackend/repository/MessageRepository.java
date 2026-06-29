package com.hao.universalassistantbackend.repository;

import com.hao.universalassistantbackend.entity.MessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<MessageEntity, UUID> {

    List<MessageEntity> findByConversation_IdOrderByCreatedAtAsc(UUID conversationId);

    List<MessageEntity> findTop20ByConversation_IdOrderByCreatedAtDesc(UUID conversationId);

    long countByConversation_Id(UUID conversationId);
}
