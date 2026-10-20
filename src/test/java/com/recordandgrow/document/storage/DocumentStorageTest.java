package com.recordandgrow.document.storage;

import com.recordandgrow.config.DocumentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地存储：文件名即 sha256、先写临时文件再原子移动、删除有越界保护。
 */
class DocumentStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void storeShouldWriteFileNamedBySha256() throws IOException {
        DocumentStorage storage = storage();
        String sha = "a".repeat(64);
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);

        DocumentStorage.StoredFile stored = storage.store(
                new ByteArrayInputStream(content), sha, "txt");

        Path file = tempDir.resolve(sha + ".txt");
        assertThat(Files.exists(file)).isTrue();
        assertThat(Files.readAllBytes(file)).isEqualTo(content);
        assertThat(stored.size()).isEqualTo(content.length);
        assertThat(stored.path()).endsWith(sha + ".txt");
    }

    @Test
    void storingSameContentTwiceShouldReuseFile() throws IOException {
        DocumentStorage storage = storage();
        String sha = "b".repeat(64);

        DocumentStorage.StoredFile first = storage.store(
                new ByteArrayInputStream("one".getBytes(StandardCharsets.UTF_8)), sha, "md");
        DocumentStorage.StoredFile second = storage.store(
                new ByteArrayInputStream("two".getBytes(StandardCharsets.UTF_8)), sha, "md");

        assertThat(second.path()).isEqualTo(first.path());
        // 已存在就不再覆盖，内容仍是第一次写入的
        assertThat(Files.readString(tempDir.resolve(sha + ".md"))).isEqualTo("one");
    }

    @Test
    void deleteShouldRemoveFile() {
        DocumentStorage storage = storage();
        String sha = "c".repeat(64);
        DocumentStorage.StoredFile stored = storage.store(
                new ByteArrayInputStream("bye".getBytes(StandardCharsets.UTF_8)), sha, "txt");

        storage.delete(stored.path());

        assertThat(Files.exists(tempDir.resolve(sha + ".txt"))).isFalse();
    }

    @Test
    void deleteMissingFileShouldBeSilent() {
        storage().delete(tempDir.resolve("not-exists.txt").toString());
    }

    @Test
    void deleteOutsideStorageRootShouldBeIgnored() throws IOException {
        Path outside = tempDir.resolveSibling("outside-" + System.nanoTime() + ".txt");
        Files.writeString(outside, "keep me");
        try {
            storage().delete(outside.toString());
            assertThat(Files.exists(outside)).isTrue();
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    private DocumentStorage storage() {
        DocumentProperties properties = new DocumentProperties();
        properties.setStorageDir(tempDir.toString());
        return new LocalDocumentStorage(properties);
    }
}
