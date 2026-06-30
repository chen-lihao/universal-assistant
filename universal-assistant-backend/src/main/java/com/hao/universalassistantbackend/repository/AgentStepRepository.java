package com.hao.universalassistantbackend.repository;

import com.hao.universalassistantbackend.entity.AgentStepEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AgentStepRepository extends JpaRepository<AgentStepEntity, UUID> {

    long countByRun_Id(UUID runId);

    List<AgentStepEntity> findByRun_IdOrderByStepIndexAsc(UUID runId);

    List<AgentStepEntity> findByRun_IdInOrderByRun_IdAscStepIndexAsc(Collection<UUID> runIds);
}
