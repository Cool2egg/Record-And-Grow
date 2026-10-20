package com.recordandgrow.document.parse;

import com.recordandgrow.config.DocumentProperties;
import com.recordandgrow.infra.exception.BusinessException;
import com.recordandgrow.infra.exception.ErrorCode;
import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.detect.Detector;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.ToTextContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 用 Tika 从 PDF / Markdown / 纯文本里抽正文。
 *
 * <p>几个刻意的选择：
 * <ul>
 *   <li>MIME 用 Tika 嗅探，不信扩展名和客户端声明的 Content-Type——伪装扩展名在这里现形；</li>
 *   <li>解析放进虚拟线程并带超时：坏文件可能让解析器卡很久，超时取消后判 422；</li>
 *   <li>只接受 {@code application/pdf} 与 {@code text/*}：白名单之外一律 415，
 *       避免用户把图片、压缩包改名后传进来走到这里；</li>
 *   <li>文本过短判失败并提示「可能是扫描件」——不做 OCR，扫描件本来就没有正文。</li>
 * </ul>
 */
public final class DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(DocumentParser.class);

    /** 正文少于此字符数视为空文档/扫描件。 */
    private static final int MIN_TEXT_LENGTH = 50;

    private final DocumentProperties properties;
    private final Detector detector = new DefaultDetector();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public DocumentParser(DocumentProperties properties) {
        this.properties = properties;
    }

    public ParsedDocument parse(InputStream input) {
        byte[] bytes = readAll(input);
        if (bytes.length == 0) {
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED, "文件是空的");
        }
        String mimeType = detectMime(bytes);
        requireSupported(mimeType);

        String text = extractWithTimeout(bytes, mimeType);
        String stripped = text.strip();
        int codePoints = stripped.codePointCount(0, stripped.length());
        if (codePoints < MIN_TEXT_LENGTH) {
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED,
                    "解析出的文本过短（" + codePoints + " 字），可能是扫描件或空文档");
        }
        if (codePoints > properties.getMaxChars()) {
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED,
                    "文档文本超过上限 " + properties.getMaxChars() + " 字");
        }
        return new ParsedDocument(stripped, mimeType);
    }

    /** 只嗅探 MIME，不解析正文；上传落库时用来填 {@code document.mime_type}。 */
    public String detectMime(byte[] bytes) {
        return detect(bytes).getBaseType().toString().toLowerCase(Locale.ROOT);
    }

    /** 白名单校验：不是 PDF / text/* 就抛 415。 */
    public void requireSupported(String mimeType) {
        if (!isSupported(mimeType)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    "不支持的文件类型: " + mimeType + "（只支持 PDF / Markdown / TXT）");
        }
    }

    private MediaType detect(byte[] bytes) {
        Metadata metadata = new Metadata();
        try (TikaInputStream stream = TikaInputStream.get(bytes)) {
            return detector.detect(stream, metadata);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED, "文件类型识别失败");
        }
    }

    private String extractWithTimeout(byte[] bytes, String mimeType) {
        Future<String> future = executor.submit(() -> extract(bytes));
        try {
            return future.get(properties.getParseTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("文档解析超时: mime={}, timeout={}", mimeType, properties.getParseTimeout());
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED,
                    "解析超时（超过 " + properties.getParseTimeout().toSeconds() + " 秒）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED, "解析被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("文档解析失败: mime={}, error={}", mimeType, cause.toString());
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED,
                    "解析失败: " + shorten(cause.getMessage()));
        }
    }

    private String extract(byte[] bytes) throws Exception {
        AutoDetectParser parser = new AutoDetectParser();
        StringWriter writer = new StringWriter();
        try (InputStream stream = new ByteArrayInputStream(bytes)) {
            parser.parse(stream, new ToTextContentHandler(writer), new Metadata(), new ParseContext());
        }
        return writer.toString();
    }

    private static boolean isSupported(String baseType) {
        return "application/pdf".equals(baseType) || baseType.startsWith("text/");
    }

    private static byte[] readAll(InputStream input) {
        try {
            return input.readAllBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED, "读取文件内容失败");
        }
    }

    private static String shorten(String message) {
        if (message == null || message.isBlank()) {
            return "未知错误";
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
