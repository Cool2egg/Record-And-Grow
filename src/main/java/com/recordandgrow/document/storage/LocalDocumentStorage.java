package com.recordandgrow.document.storage;

import com.recordandgrow.config.DocumentProperties;
import com.recordandgrow.infra.exception.BusinessException;
import com.recordandgrow.infra.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * 本地文件存储。
 *
 * <p>文件名直接用 sha256，天然去重；先写临时文件再原子移动，避免进程被杀时留下半截文件。
 * 落库的是配置里的相对路径（如 {@code data/documents/xxx.pdf}），不是宿主机绝对路径，
 * 换机器时不必改库。
 */
public final class LocalDocumentStorage implements DocumentStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalDocumentStorage.class);

    /** 配置里的路径，作为落库值与展示值。 */
    private final Path displayRoot;

    /** 绝对路径，用于创建目录与越界校验。 */
    private final Path absoluteRoot;

    public LocalDocumentStorage(DocumentProperties properties) {
        this.displayRoot = Paths.get(properties.getStorageDir());
        this.absoluteRoot = displayRoot.toAbsolutePath().normalize();
    }

    @Override
    public StoredFile store(InputStream input, String sha256, String extension) {
        String safeExtension = extension == null
                ? "bin"
                : extension.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (safeExtension.isEmpty()) {
            safeExtension = "bin";
        }
        String fileName = sha256 + "." + safeExtension;
        Path target = absoluteRoot.resolve(fileName);
        try {
            Files.createDirectories(absoluteRoot);
            if (Files.exists(target)) {
                return new StoredFile(displayRoot.resolve(fileName).toString(), Files.size(target));
            }
            Path temp = Files.createTempFile(absoluteRoot, ".upload-", ".tmp");
            try {
                long size = Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(temp, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return new StoredFile(displayRoot.resolve(fileName).toString(), size);
            } catch (IOException e) {
                Files.deleteIfExists(temp);
                throw e;
            }
        } catch (IOException e) {
            log.warn("保存文档原文失败: file={}, error={}", fileName, e.toString());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存文档原文失败");
        }
    }

    @Override
    public void delete(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            return;
        }
        Path path = Paths.get(storedPath).toAbsolutePath().normalize();
        // 只允许删存储目录内的文件，防止路径穿越误删
        if (!path.startsWith(absoluteRoot)) {
            log.warn("拒绝删除存储目录之外的文件: {}", storedPath);
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // 删文件失败不应让「删除文档」整体失败：数据库记录已经软删，
            // 残留文件最多占点磁盘，记日志即可。
            log.warn("删除文档原文失败: path={}, error={}", storedPath, e.toString());
        }
    }
}
