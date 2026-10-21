package com.recordandgrow.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.recordandgrow.ai.EmbeddingClient;
import com.recordandgrow.ai.config.AiProperties;
import com.recordandgrow.config.DocumentProperties;
import com.recordandgrow.document.chunk.Chunk;
import com.recordandgrow.document.chunk.ChunkResult;
import com.recordandgrow.document.chunk.Chunker;
import com.recordandgrow.document.chunk.TextNormalizer;
import com.recordandgrow.document.dto.DocumentChunkResponse;
import com.recordandgrow.document.dto.DocumentRegisterResult;
import com.recordandgrow.document.dto.DocumentResponse;
import com.recordandgrow.document.parse.DocumentParser;
import com.recordandgrow.document.parse.ParsedDocument;
import com.recordandgrow.document.queue.DocumentIngestQueue;
import com.recordandgrow.document.storage.DocumentStorage;
import com.recordandgrow.infra.exception.BusinessException;
import com.recordandgrow.infra.exception.ErrorCode;
import com.recordandgrow.infra.response.PageResult;
import org.apache.ibatis.exceptions.PersistenceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 文档入库编排：注册、解析、向量回填、重建、删除与查询。
 *
 * <p>几条贯穿全类的约定：
 * <ul>
 *   <li>状态迁移用「带前置条件的 UPDATE」做 CAS，影响 0 行就安静退出；</li>
 *   <li>{@code ingestParse / ingestEmbed} 不加 {@code @Transactional}：它们要的是
 *       「每步各自提交」的断点，而不是一个大事务；</li>
 *   <li>发消息一律在写库之后，且在调用方事务提交之后（controller 负责）；</li>
 *   <li>每次写库前重新查一次文档，发现已删除就停手。</li>
 * </ul>
 */
@Service
public class DocumentIngestService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestService.class);

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_ERROR_MESSAGE = 500;
    private static final int MAX_TITLE_LENGTH = 200;

    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper chunkMapper;
    private final DocumentChunkWriter chunkWriter;
    private final DocumentStorage storage;
    private final DocumentParser parser;
    private final Chunker chunker;
    private final EmbeddingClient embeddingClient;
    private final DocumentIngestQueue queue;
    private final DocumentProperties properties;
    private final AiProperties aiProperties;

    public DocumentIngestService(DocumentMapper documentMapper,
                                 DocumentChunkMapper chunkMapper,
                                 DocumentChunkWriter chunkWriter,
                                 DocumentStorage storage,
                                 DocumentParser parser,
                                 Chunker chunker,
                                 EmbeddingClient embeddingClient,
                                 DocumentIngestQueue queue,
                                 DocumentProperties properties,
                                 AiProperties aiProperties) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.chunkWriter = chunkWriter;
        this.storage = storage;
        this.parser = parser;
        this.chunker = chunker;
        this.embeddingClient = embeddingClient;
        this.queue = queue;
        this.properties = properties;
        this.aiProperties = aiProperties;
    }

    /**
     * 接收上传：校验 → 哈希 → 去重 → 落盘 → 建 PENDING 记录。
     *
     * <p>不发消息：调用方在事务提交后再调 {@code queue.sendParse}，
     * 否则消费端可能查不到刚插入的行，把任务当成「不存在」丢掉。
     */
    public DocumentRegisterResult register(String originalFileName, long size,
                                           Supplier<InputStream> content) {
        if (!embeddingClient.isConfigured()) {
            throw new BusinessException(ErrorCode.AI_UNAVAILABLE,
                    "Embedding 未配置（DASHSCOPE_API_KEY 为空），无法入库");
        }
        String fileName = sanitizeFileName(originalFileName);
        String extension = extensionOf(fileName);
        if (!properties.getAllowedExtensions().contains(extension)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    "不支持的文件扩展名: " + originalFileName);
        }
        if (size > properties.getMaxFileSize().toBytes()) {
            throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "文件超过上限 " + properties.getMaxFileSize().toMegabytes() + "MB");
        }

        byte[] bytes = readAll(content);
        if (bytes.length == 0) {
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED, "文件是空的");
        }
        String sha256 = sha256Hex(bytes);

        Document existing = documentMapper.selectActiveBySha256(sha256);
        if (existing != null) {
            return duplicate(existing);
        }

        String mimeType = parser.detectMime(bytes);
        parser.requireSupported(mimeType);
        DocumentStorage.StoredFile stored = storage.store(new ByteArrayInputStream(bytes), sha256, extension);

        Document document = new Document();
        document.setTitle(titleOf(fileName));
        document.setFileName(fileName);
        document.setMimeType(mimeType);
        document.setFileSize((long) bytes.length);
        document.setStoragePath(stored.path());
        document.setContentSha256(sha256);
        document.setStatus(DocumentStatus.PENDING.name());
        try {
            documentMapper.insert(document);
            return new DocumentRegisterResult(DocumentResponse.from(document), false, true);
        } catch (DuplicateKeyException e) {
            return duplicateAfterRace(sha256, e);
        } catch (PersistenceException e) {
            if (!isUniqueViolation(e)) {
                throw e;
            }
            return duplicateAfterRace(sha256, e);
        }
    }

    /** 解析 → 归一化 → 切块 → 落库（embedding 为空）→ 发 embed。可重复执行。 */
    public void ingestParse(Long documentId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            log.info("文档不存在或已删除，跳过 parse: id={}", documentId);
            return;
        }
        String status = document.getStatus();
        if (DocumentStatus.READY.name().equals(status)) {
            return;
        }
        if (DocumentStatus.EMBEDDING.name().equals(status)) {
            // 重复投递：正文与分块都已经在库里，只需要保证 embed 消息不丢
            queue.sendEmbed(documentId);
            return;
        }
        if (documentMapper.markParsing(documentId) == 0) {
            log.info("状态 CAS 失败，跳过 parse: id={}, status={}", documentId, status);
            return;
        }

        String normalized = document.getNormalizedText();
        if (normalized == null || normalized.isBlank()) {
            ParsedDocument parsed = parseStoredFile(document);
            normalized = TextNormalizer.normalize(parsed.text());
            if (normalized.isBlank()) {
                throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED, "归一化后没有正文");
            }
            documentMapper.saveNormalizedText(documentId, normalized,
                    normalized.codePointCount(0, normalized.length()));
        }

        ChunkResult result = chunker.chunk(normalized);
        if (result.chunks().isEmpty()) {
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED, "没有可入库的正文");
        }

        // 覆盖式重写：同一文档的重复解析先清空旧块，天然幂等
        chunkMapper.deleteByDocumentId(documentId);
        int tokenSum = 0;
        for (Chunk chunk : result.chunks()) {
            DocumentChunk entity = new DocumentChunk();
            entity.setDocumentId(documentId);
            entity.setChunkIndex(chunk.index());
            entity.setContent(chunk.content());
            entity.setHeading(chunk.heading());
            entity.setCharStart(chunk.charStart());
            entity.setCharEnd(chunk.charEnd());
            entity.setCharCount(chunk.charCount());
            entity.setTokenCount(chunk.tokenCount());
            chunkMapper.insert(entity);
            tokenSum += chunk.tokenCount();
        }

        int charCount = normalized.codePointCount(0, normalized.length());
        int updated = documentMapper.markEmbedding(documentId, result.chunks().size(),
                charCount, tokenSum, result.truncated());
        if (updated == 0) {
            log.info("分块已落库但文档状态已变化（可能被删除），不再发 embed: id={}", documentId);
            return;
        }
        queue.sendEmbed(documentId);
    }

    /** 按批回填向量：每次只取 embedding 为空的块，失败后从断点继续。 */
    public void ingestEmbed(Long documentId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null || DocumentStatus.READY.name().equals(document.getStatus())) {
            return;
        }
        if (!DocumentStatus.EMBEDDING.name().equals(document.getStatus())) {
            log.info("忽略 embed 消息：文档不在 EMBEDDING 状态: id={}, status={}",
                    documentId, document.getStatus());
            return;
        }

        int batchSize = Math.max(1, aiProperties.getEmbedding().getBatchSize());
        String model = aiProperties.getEmbedding().getModel();
        while (true) {
            // 每批前二次确认没有被删：消费过程中用户可能删了文档
            Document current = documentMapper.selectById(documentId);
            if (current == null) {
                log.info("文档已删除，停止 embedding: id={}", documentId);
                return;
            }
            List<DocumentChunk> batch = chunkMapper.selectPendingBatch(documentId, batchSize);
            if (batch.isEmpty()) {
                break;
            }
            List<String> inputs = new ArrayList<>(batch.size());
            for (DocumentChunk chunk : batch) {
                inputs.add(embeddingInput(chunk));
            }
            // embedding 调用绝不在事务里
            List<float[]> vectors = embeddingClient.embed(inputs);
            chunkWriter.writeBatch(batch, vectors, model);
            documentMapper.touchProgress(documentId);
        }

        int chunkCount = chunkMapper.countByDocumentId(documentId);
        int tokenCount = chunkMapper.sumTokenCount(documentId);
        documentMapper.markReady(documentId, chunkCount, tokenCount);
    }

    /** 监听器与 local 队列在重试耗尽后调用。 */
    public void markFailed(Long documentId, String message) {
        documentMapper.markFailed(documentId, truncateError(message));
    }

    /** READY / FAILED → PENDING，清空旧块后重跑。处理中直接 409。 */
    public void reindex(Long documentId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        if (documentMapper.resetForReindex(documentId) == 0) {
            throw new BusinessException(ErrorCode.DOCUMENT_BUSY, "文档正在处理中，不能重建索引");
        }
        chunkMapper.deleteByDocumentId(documentId);
        queue.sendParse(documentId);
    }

    /** 软删 + 删分块 + 删原文；处理中 409。 */
    @Transactional
    public void delete(Long documentId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        String status = document.getStatus();
        if (DocumentStatus.PARSING.name().equals(status) || DocumentStatus.EMBEDDING.name().equals(status)) {
            throw new BusinessException(ErrorCode.DOCUMENT_BUSY, "文档正在处理中，不能删除");
        }
        documentMapper.deleteById(documentId);
        chunkMapper.deleteByDocumentId(documentId);
        storage.delete(document.getStoragePath());
    }

    public PageResult<DocumentResponse> list(int page, int size, String status) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));

        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            wrapper.eq(Document::getStatus, parseStatus(status));
        }
        wrapper.orderByDesc(Document::getCreatedAt).orderByDesc(Document::getId);

        Page<Document> result = documentMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        List<DocumentResponse> items = result.getRecords().stream()
                .map(DocumentResponse::from)
                .toList();
        return PageResult.of(items, result.getTotal(), result.getCurrent(), result.getSize());
    }

    public DocumentResponse get(Long documentId) {
        return DocumentResponse.from(mustExist(documentId));
    }

    public PageResult<DocumentChunkResponse> chunks(Long documentId, int page, int size) {
        mustExist(documentId);
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));

        LambdaQueryWrapper<DocumentChunk> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DocumentChunk::getDocumentId, documentId)
                .orderByAsc(DocumentChunk::getChunkIndex);

        Page<DocumentChunk> result = chunkMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        List<DocumentChunkResponse> items = result.getRecords().stream()
                .map(DocumentChunkResponse::from)
                .toList();
        return PageResult.of(items, result.getTotal(), result.getCurrent(), result.getSize());
    }

    private Document mustExist(Long documentId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        return document;
    }

    private ParsedDocument parseStoredFile(Document document) {
        Path path = Paths.get(document.getStoragePath());
        try (InputStream input = Files.newInputStream(path)) {
            return parser.parse(input);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED, "原文文件不存在或不可读");
        }
    }

    private String embeddingInput(DocumentChunk chunk) {
        String heading = chunk.getHeading();
        if (heading == null || heading.isBlank()) {
            return chunk.getContent();
        }
        return heading + "\n" + chunk.getContent();
    }

    private DocumentRegisterResult duplicate(Document existing) {
        boolean pending = DocumentStatus.PENDING.name().equals(existing.getStatus());
        // 去重命中且仍在 PENDING：可能上次发送失败，补发一次，否则会永久卡住
        return new DocumentRegisterResult(DocumentResponse.from(existing), true, pending);
    }

    private DocumentRegisterResult duplicateAfterRace(String sha256, RuntimeException cause) {
        Document existing = documentMapper.selectActiveBySha256(sha256);
        if (existing != null) {
            return duplicate(existing);
        }
        throw cause;
    }

    private static boolean isUniqueViolation(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null) {
            if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static String parseStatus(String status) {
        try {
            return DocumentStatus.valueOf(status.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "status 只能是 PENDING / PARSING / EMBEDDING / READY / FAILED");
        }
    }

    private static byte[] readAll(Supplier<InputStream> content) {
        try (InputStream input = content.get()) {
            return input.readAllBytes();
        } catch (IOException | RuntimeException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "读取上传内容失败");
        }
    }

    private static String sanitizeFileName(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) {
            return "document";
        }
        String name = Paths.get(originalFileName).getFileName().toString();
        return name.isBlank() ? "document" : name;
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String titleOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String title = dot > 0 ? fileName.substring(0, dot) : fileName;
        return title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title;
    }

    private static String truncateError(String message) {
        String text = message == null || message.isBlank() ? "处理失败" : message;
        return text.length() > MAX_ERROR_MESSAGE ? text.substring(0, MAX_ERROR_MESSAGE) : text;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
