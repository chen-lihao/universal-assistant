package com.hao.universalassistantbackend.context;

import com.hao.universalassistantbackend.model.ChatMessage;
import com.hao.universalassistantbackend.model.MemoryHit;
import com.hao.universalassistantbackend.model.SearchResult;
import com.hao.universalassistantbackend.model.WeatherReport;
import com.hao.universalassistantbackend.skill.ActiveSkill;

import java.util.List;

public record ContextRequest(
        String message,
        String conversationSummary,
        List<ChatMessage> history,
        List<SearchResult> sources,
        WeatherReport weatherReport,
        List<MemoryHit> memories,
        List<ActiveSkill> skills,
        String plan,
        String modelId,
        String modelDisplayName,
        String modelProvider,
        String modelIntegration
) {
}
