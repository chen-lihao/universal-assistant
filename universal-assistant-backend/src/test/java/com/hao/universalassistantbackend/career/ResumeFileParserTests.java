package com.hao.universalassistantbackend.career;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResumeFileParserTests {

    private final ResumeFileParser parser = new ResumeFileParser();

    @Test
    void parsesMarkdownAndPreservesSourceFormat() {
        var result = parser.parse(file("resume.md", "# Resume\nJava experience".getBytes(StandardCharsets.UTF_8)));
        assertThat(result.format()).isEqualTo("md");
        assertThat(result.content()).contains("Java experience");
    }

    @Test
    void extractsTextFromPdf() throws Exception {
        byte[] bytes;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText("Backend engineer with PostgreSQL experience");
                stream.endText();
            }
            document.save(output);
            bytes = output.toByteArray();
        }
        var result = parser.parse(file("resume.pdf", bytes));
        assertThat(result.format()).isEqualTo("pdf");
        assertThat(result.content()).contains("PostgreSQL experience");
        assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    void extractsTextFromDocx() throws Exception {
        byte[] bytes;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Spring Boot developer");
            document.write(output);
            bytes = output.toByteArray();
        }
        var result = parser.parse(file("resume.docx", bytes));
        assertThat(result.format()).isEqualTo("docx");
        assertThat(result.content()).contains("Spring Boot developer");
    }

    @Test
    void rejectsMismatchedOrEmptyFiles() {
        assertThatThrownBy(() -> parser.parse(file("resume.pdf", "not a PDF".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("扩展名不符");
        assertThatThrownBy(() -> parser.parse(file("resume.md", new byte[0])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非空");
    }

    private MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "application/octet-stream", bytes);
    }
}
