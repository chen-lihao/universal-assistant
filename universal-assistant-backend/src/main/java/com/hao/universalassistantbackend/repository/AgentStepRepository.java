package com.hao.universalassistantbackend.repository;

import com.hao.universalassistantbackend.entity.AgentStepEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AgentStepRepository extends JpaRepository<AgentStepEntity, UUID> {

    long countByRun_Id(UUID runId);
}
