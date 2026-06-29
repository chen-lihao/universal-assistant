package com.hao.universalassistantbackend.repository;

import com.hao.universalassistantbackend.entity.AgentRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AgentRunRepository extends JpaRepository<AgentRunEntity, UUID> {
}
