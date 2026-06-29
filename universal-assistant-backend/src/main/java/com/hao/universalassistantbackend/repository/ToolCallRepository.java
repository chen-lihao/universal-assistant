package com.hao.universalassistantbackend.repository;

import com.hao.universalassistantbackend.entity.ToolCallEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ToolCallRepository extends JpaRepository<ToolCallEntity, UUID> {
}
