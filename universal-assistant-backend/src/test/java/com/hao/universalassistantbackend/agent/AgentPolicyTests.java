package com.hao.universalassistantbackend.agent;

import com.hao.universalassistantbackend.model.AgentMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPolicyTests {

    private final AgentPolicy policy = new AgentPolicy();

    @Test
    void selectsReactForToolDrivenRequests() {
        assertThat(policy.selectMode("广州明天天气", true, false)).isEqualTo(AgentMode.REACT);
        assertThat(policy.selectMode("搜索最新版本", false, true)).isEqualTo(AgentMode.REACT);
    }

    @Test
    void selectsPlanAndSolveForComplexRequests() {
        assertThat(policy.selectMode("请设计一个分步骤实施方案", false, false)).isEqualTo(AgentMode.PLAN_AND_SOLVE);
    }

    @Test
    void selectsDirectForSimpleConversation() {
        assertThat(policy.selectMode("你是谁？", false, false)).isEqualTo(AgentMode.DIRECT);
    }
}
