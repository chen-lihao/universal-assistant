package com.hao.universalassistantbackend.controller;

import com.hao.universalassistantbackend.skill.SkillDescriptor;
import com.hao.universalassistantbackend.skill.SkillService;
import com.hao.universalassistantbackend.tool.ToolDescriptor;
import com.hao.universalassistantbackend.tool.ToolRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/capabilities")
public class CapabilityController {

    private final SkillService skillService;
    private final ToolRegistry toolRegistry;

    public CapabilityController(SkillService skillService, ToolRegistry toolRegistry) {
        this.skillService = skillService;
        this.toolRegistry = toolRegistry;
    }

    @GetMapping("/skills")
    public List<SkillDescriptor> skills() {
        return skillService.listSkills();
    }

    @GetMapping("/tools")
    public List<ToolDescriptor> tools() {
        return toolRegistry.list();
    }
}
