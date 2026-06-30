package com.hao.universalassistantbackend.repository;

import com.hao.universalassistantbackend.entity.AgentRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AgentRunRepository extends JpaRepository<AgentRunEntity, UUID> {

    List<AgentRunEntity> findByAssistantMessageIdIn(Collection<UUID> assistantMessageIds);

    List<AgentRunEntity> findByConversation_IdAndInvalidatedAtIsNullAndAssistantMessageIdIsNullOrderByCreatedAtAsc(UUID conversationId);

    @Query("select run from AgentRunEntity run join fetch run.conversation where run.id = :runId")
    java.util.Optional<AgentRunEntity> findByIdWithConversation(@Param("runId") UUID runId);

    @Modifying
    @Query("""
            update AgentRunEntity run
            set run.status = 'invalidated',
                run.invalidatedAt = :invalidatedAt,
                run.completedAt = coalesce(run.completedAt, :invalidatedAt)
            where run.conversation.id = :conversationId
              and run.invalidatedAt is null
              and (run.createdAt > :createdAt or run.userMessageId = :messageId)
            """)
    int invalidateAfterOrForUserMessage(@Param("conversationId") UUID conversationId,
                                        @Param("messageId") UUID messageId,
                                        @Param("createdAt") Instant createdAt,
                                        @Param("invalidatedAt") Instant invalidatedAt);
}
