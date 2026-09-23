package com.hao.universalassistantbackend.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextChunkerTests {

    @Test
    void splitsLongTextAtNaturalBoundariesWithOverlap() {
        TextChunker chunker = new TextChunker(300, 40);
        String content = "第一段。".repeat(70) + "\n第二段。".repeat(70);

        List<String> chunks = chunker.split(content);

        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(chunks).allMatch(chunk -> !chunk.isBlank() && chunk.length() <= 300);
    }

    @Test
    void ignoresBlankContent() {
        assertThat(new TextChunker(900, 120).split("  \n ")).isEmpty();
    }
}
