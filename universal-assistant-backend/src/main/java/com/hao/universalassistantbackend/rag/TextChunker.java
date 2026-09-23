package com.hao.universalassistantbackend.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Component
public class TextChunker {

    private final int chunkSize;
    private final int overlap;

    public TextChunker(@Value("${assistant.rag.chunk-size:900}") int chunkSize,
                       @Value("${assistant.rag.chunk-overlap:120}") int overlap) {
        this.chunkSize = Math.max(300, chunkSize);
        this.overlap = Math.max(0, Math.min(overlap, this.chunkSize / 3));
    }

    public List<String> split(String content) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }

        String normalized = content.replace("\r\n", "\n").replace('\r', '\n').trim();
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int targetEnd = Math.min(normalized.length(), start + chunkSize);
            int end = findBoundary(normalized, start, targetEnd);
            String chunk = normalized.substring(start, end).trim();
            if (StringUtils.hasText(chunk)) {
                chunks.add(chunk);
            }
            if (end >= normalized.length()) {
                break;
            }
            start = Math.max(start + 1, end - overlap);
        }
        return chunks;
    }

    private int findBoundary(String content, int start, int targetEnd) {
        if (targetEnd >= content.length()) {
            return content.length();
        }
        int minimum = Math.min(targetEnd, start + chunkSize / 2);
        for (int index = targetEnd; index > minimum; index--) {
            char ch = content.charAt(index - 1);
            if (ch == '\n' || ch == '。' || ch == '！' || ch == '？' || ch == '.' || ch == '!' || ch == '?') {
                return index;
            }
        }
        return targetEnd;
    }
}
