package com.recordandgrow.document.parse;

import com.recordandgrow.config.DocumentProperties;
import com.recordandgrow.infra.exception.BusinessException;
import com.recordandgrow.infra.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Tika 解析：三种格式、坏文件、空文件、伪装扩展名、超长文本。
 *
 * <p>fixture 在 {@code src/test/resources/fixtures}；PDF 是手工构造的最小单页文件。
 */
class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser(new DocumentProperties());

    @Test
    void shouldParsePlainText() {
        ParsedDocument document = parser.parse(open("sample.txt"));

        assertThat(document.mimeType()).isEqualTo("text/plain");
        assertThat(document.text()).contains("RecordAndGrow").contains("文档入库");
    }

    @Test
    void shouldParseMarkdown() {
        ParsedDocument document = parser.parse(open("sample.md"));

        assertThat(document.text()).contains("文档入库").contains("检索");
    }

    @Test
    void shouldParsePdf() {
        ParsedDocument document = parser.parse(open("sample.pdf"));

        assertThat(document.mimeType()).isEqualTo("application/pdf");
        assertThat(document.text()).contains("Hello RecordAndGrow");
    }

    @Test
    void brokenPdfShouldFailAsBusinessException() {
        BusinessException e = catchThrowableOfType(
                () -> parser.parse(open("broken.pdf")), BusinessException.class);

        assertThat(e).isNotNull();
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DOCUMENT_PARSE_FAILED);
    }

    @Test
    void emptyFileShouldFail() {
        BusinessException e = catchThrowableOfType(
                () -> parser.parse(new ByteArrayInputStream(new byte[0])), BusinessException.class);

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DOCUMENT_PARSE_FAILED);
    }

    @Test
    void disguisedExtensionShouldBeDetectedByContent() {
        // 文件叫 .txt，内容其实是 PDF：MIME 必须按内容嗅探
        ParsedDocument document = parser.parse(open("disguised.txt"));

        assertThat(document.mimeType()).isEqualTo("application/pdf");
        assertThat(document.text()).contains("Hello RecordAndGrow");
    }

    @Test
    void tooShortTextShouldFailWithScanHint() {
        InputStream input = new ByteArrayInputStream("太短了。".getBytes(StandardCharsets.UTF_8));

        BusinessException e = catchThrowableOfType(
                () -> parser.parse(input), BusinessException.class);

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DOCUMENT_PARSE_FAILED);
        assertThat(e.getMessage()).contains("扫描件");
    }

    @Test
    void textOverMaxCharsShouldFail() {
        DocumentProperties properties = new DocumentProperties();
        properties.setMaxChars(10);
        DocumentParser tinyLimitParser = new DocumentParser(properties);

        BusinessException e = catchThrowableOfType(
                () -> tinyLimitParser.parse(open("sample.txt")), BusinessException.class);

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DOCUMENT_PARSE_FAILED);
        assertThat(e.getMessage()).contains("上限");
    }

    @Test
    void unsupportedMimeShouldFailAs415() {
        // ZIP 魔数；白名单只放 application/pdf 与 text/*
        byte[] zipLike = new byte[]{0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x08, 0x00};

        BusinessException e = catchThrowableOfType(
                () -> parser.parse(new ByteArrayInputStream(zipLike)), BusinessException.class);

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    private InputStream open(String name) {
        try {
            Path path = Path.of("src", "test", "resources", "fixtures", name);
            return Files.newInputStream(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
