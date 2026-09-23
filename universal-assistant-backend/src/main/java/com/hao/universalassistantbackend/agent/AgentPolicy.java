package com.hao.universalassistantbackend.agent;

import com.hao.universalassistantbackend.model.AgentMode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;

@Component
public class AgentPolicy {

    public AgentMode selectMode(String message, boolean weatherIntent, boolean realtimeSearch) {
        if (weatherIntent || realtimeSearch) {
            return AgentMode.REACT;
        }
        if (isComplexTask(message)) {
            return AgentMode.PLAN_AND_SOLVE;
        }
        return AgentMode.DIRECT;
    }

    public boolean isComplexTask(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }

        String normalized = message.toLowerCase(Locale.ROOT);
        return message.length() >= 80
                || normalized.contains("计划")
                || normalized.contains("方案")
                || normalized.contains("步骤")
                || normalized.contains("分析")
                || normalized.contains("比较")
                || normalized.contains("调研")
                || normalized.contains("设计")
                || normalized.contains("实现")
                || normalized.contains("排查")
                || normalized.contains("优化")
                || normalized.contains("plan")
                || normalized.contains("analyze")
                || normalized.contains("compare")
                || normalized.contains("design")
                || normalized.contains("implement");
    }
}
