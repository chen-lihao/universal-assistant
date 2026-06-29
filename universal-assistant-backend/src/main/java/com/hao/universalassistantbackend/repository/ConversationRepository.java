package com.hao.universalassistantbackend.repository;

import com.hao.universalassistantbackend.entity.ConversationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<ConversationEntity, UUID> {

    List<ConversationEntity> findTop50ByArchivedFalseOrderByUpdatedAtDesc();
}
