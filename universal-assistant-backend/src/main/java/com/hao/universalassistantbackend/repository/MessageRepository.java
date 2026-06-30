package com.hao.universalassistantbackend.repository;

import com.hao.universalassistantbackend.entity.MessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<MessageEntity, UUID> {

    List<MessageEntity> findByConversation_IdAndInvalidatedAtIsNullOrderByCreatedAtAsc(UUID conversationId);

    List<MessageEntity> findTop20ByConversation_IdAndInvalidatedAtIsNullOrderByCreatedAtDesc(UUID conversationId);

    List<MessageEntity> findTop20ByConversation_IdAndInvalidatedAtIsNullAndCreatedAtBeforeOrderByCreatedAtDesc(UUID conversationId, Instant createdAt);

    long countByConversation_IdAndInvalidatedAtIsNull(UUID conversationId);

    @Modifying
    @Query("""
            update MessageEntity message
            set message.invalidatedAt = :invalidatedAt
            where message.conversation.id = :conversationId
              and message.invalidatedAt is null
              and message.createdAt > :createdAt
            """)
    int invalidateAfter(@Param("conversationId") UUID conversationId,
                        @Param("createdAt") Instant createdAt,
                        @Param("invalidatedAt") Instant invalidatedAt);
}
