package com.hao.universalassistantbackend.model;

import com.hao.universalassistantbackend.entity.AgentRunEntity;

import java.util.ArrayList;
import java.util.List;

public class AgentRunContext {

    private final AgentRunEntity run;
    private final AgentMode mode;
    private final List<AgentStepResponse> steps = new ArrayList<>();

    public AgentRunContext(AgentRunEntity run, AgentMode mode) {
        this.run = run;
        this.mode = mode;
    }

    public AgentRunEntity run() {
        return run;
    }

    public AgentMode mode() {
        return mode;
    }

    public List<AgentStepResponse> steps() {
        return steps;
    }

    public void addStep(AgentStepResponse step) {
        steps.add(step);
    }
}
