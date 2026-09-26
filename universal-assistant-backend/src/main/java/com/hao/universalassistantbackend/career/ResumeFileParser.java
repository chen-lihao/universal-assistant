package com.hao.universalassistantbackend.career;

import com.hao.universalassistantbackend.career.CareerModels.ParsedResume;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

@Service
public class ResumeFileParser {

    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final int MAX_TEXT_CHARS = 100_000;
    private static final int MAX_PDF_PAGES = 30;
    private static final byte[] OLE_HEADER = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0};

    public ParsedResume parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择非空的简历文件。");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("简历文件不能超过 5 MB。");
        }
        String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        fileName = fileName.replace('\\', '/');
        fileName = fileName.substring(fileName.lastIndexOf('/') + 1);
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        try {
            byte[] bytes = file.getBytes();
            String format = formatOf(lowerName);
            String content = switch (format) {
                case "pdf" -> readPdf(bytes);
                case "docx" -> readDocx(bytes);
                case "doc" -> readDoc(bytes);
                default -> readMarkdown(bytes);
            };
            content = content.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "").trim();
            if (content.isBlank()) {
                throw new IllegalArgumentException("未提取到可用文字。扫描版 PDF 请先完成 OCR，再导入文字版文件。");
            }
            if (content.length() > MAX_TEXT_CHARS) {
                throw new IllegalArgumentException("简历文字不能超过 10 万字，请精简后导入。");
            }
            List<String> warnings = format.equals("pdf") && content.length() < 80
                    ? List.of("PDF 可提取文字较少，请核对是否遗漏了扫描图片中的内容。")
                    : List.of();
            return new ParsedResume(fileName, format, content, warnings);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (InvalidPasswordException ex) {
            throw new IllegalArgumentException("PDF 已加密，请先解除密码保护后导入。", ex);
        } catch (IOException | RuntimeException ex) {
            throw new IllegalArgumentException("无法解析简历文件，请检查文件是否损坏或与扩展名不符。", ex);
        }
    }

    private String formatOf(String fileName) {
        if (fileName.endsWith(".pdf")) return "pdf";
        if (fileName.endsWith(".docx")) return "docx";
        if (fileName.endsWith(".doc")) return "doc";
        if (fileName.endsWith(".md") || fileName.endsWith(".markdown")) return "md";
        throw new IllegalArgumentException("仅支持 PDF、Word（DOCX/DOC）和 Markdown 简历。");
    }

    private String readPdf(byte[] bytes) throws IOException {
        requireHeader(bytes, new byte[]{'%', 'P', 'D', 'F', '-'});
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.getNumberOfPages() > MAX_PDF_PAGES) {
                throw new IllegalArgumentException("PDF 简历不能超过 30 页。");
            }
            return new PDFTextStripper().getText(document);
        }
    }

    private String readDocx(byte[] bytes) throws IOException {
        requireHeader(bytes, new byte[]{'P', 'K'});
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    private String readDoc(byte[] bytes) throws IOException {
        requireHeader(bytes, OLE_HEADER);
        try (WordExtractor extractor = new WordExtractor(new ByteArrayInputStream(bytes))) {
            return extractor.getText();
        }
    }

    private String readMarkdown(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private void requireHeader(byte[] bytes, byte[] header) {
        if (bytes.length < header.length) {
            throw new IllegalArgumentException("文件内容与扩展名不符。");
        }
        for (int index = 0; index < header.length; index++) {
            if (bytes[index] != header[index]) {
                throw new IllegalArgumentException("文件内容与扩展名不符。");
            }
        }
    }
}
