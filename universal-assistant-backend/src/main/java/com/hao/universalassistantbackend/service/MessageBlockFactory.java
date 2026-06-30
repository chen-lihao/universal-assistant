package com.hao.universalassistantbackend.service;

import com.hao.universalassistantbackend.model.AgentRunContext;
import com.hao.universalassistantbackend.model.MessageBlock;
import com.hao.universalassistantbackend.model.SearchResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class MessageBlockFactory {

    public List<MessageBlock> assistantBlocks(String content,
                                              AgentRunContext runContext,
                                              List<SearchResult> sources,
                                              String status) {
        return assistantBlocks(content, runContext, sources, status, true);
    }

    public List<MessageBlock> legacyAssistantBlocks(String content,
                                                    AgentRunContext runContext,
                                                    List<SearchResult> sources,
                                                    String status) {
        return assistantBlocks(content, runContext, sources, status, false);
    }

    private List<MessageBlock> assistantBlocks(String content,
                                               AgentRunContext runContext,
                                               List<SearchResult> sources,
                                               String status,
                                               boolean randomIds) {
        List<MessageBlock> blocks = new ArrayList<>();
        if (runContext != null && runContext.steps() != null && !runContext.steps().isEmpty()) {
            blocks.add(MessageBlock.execution(
                    blockId(randomIds, "execution"),
                    runContext.run().getId(),
                    runContext.steps(),
                    false
            ));
        }
        if (StringUtils.hasText(content)) {
            blocks.add(MessageBlock.markdown(blockId(randomIds, "markdown"), content, false));
        }
        if (sources != null && !sources.isEmpty()) {
            blocks.add(MessageBlock.sources(blockId(randomIds, "sources"), sources));
        }
        if ("cancelled".equals(status)) {
            blocks.add(MessageBlock.status(blockId(randomIds, "status"), "cancelled", "回答已中断"));
        } else if ("failed".equals(status)) {
            blocks.add(MessageBlock.status(blockId(randomIds, "status"), "error", "回答失败"));
        }
        return blocks;
    }

    private String blockId(boolean randomIds, String suffix) {
        return randomIds ? suffix + "-" + UUID.randomUUID() : "legacy-" + suffix;
    }
}
