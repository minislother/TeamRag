package com.team.rag.service;

import com.team.rag.config.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("文档本地存储服务单元测试")
class DocumentStorageServiceTest {

    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("保存上传文件 - 清理文件名并计算哈希")
    void testStore_CleanFilenameAndCalculateHash() throws Exception {
        // Given
        RagProperties properties = new RagProperties();
        properties.getDocument().setStorageDir(tempDir.toString());
        DocumentStorageService service = new DocumentStorageService(properties);
        byte[] content = "hello rag".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "folder/redis.txt", "text/plain", content);

        // When
        StoredDocumentFile stored = service.store(file, "doc-1", 3);

        // Then
        Path storedPath = Path.of(stored.path());
        assertThat(storedPath.getFileName().toString()).isEqualTo("redis.txt");
        assertThat(storedPath).exists();
        assertThat(Files.readAllBytes(storedPath)).isEqualTo(content);
        assertThat(stored.fileHash()).hasSize(64);
        assertThat(stored.size()).isEqualTo(content.length);
    }

    @Test
    @DisplayName("保存上传文件 - 原文件名为空时使用默认文件名")
    void testStore_NullFilename_UseDefaultName() {
        // Given
        RagProperties properties = new RagProperties();
        properties.getDocument().setStorageDir(tempDir.toString());
        DocumentStorageService service = new DocumentStorageService(properties);
        MockMultipartFile file = new MockMultipartFile("file", null, "text/plain", "hello".getBytes());

        // When
        StoredDocumentFile stored = service.store(file, "doc-1", 1);

        // Then
        assertThat(Path.of(stored.path()).getFileName().toString()).isEqualTo("unknown");
        assertThat(Path.of(stored.path())).exists();
    }

    @Test
    @DisplayName("删除临时文件 - 文件存在时静默删除")
    void testDeleteQuietly_FileExists_DeleteFile() throws Exception {
        // Given
        RagProperties properties = new RagProperties();
        properties.getDocument().setStorageDir(tempDir.toString());
        DocumentStorageService service = new DocumentStorageService(properties);
        Path file = Files.writeString(tempDir.resolve("orphan.txt"), "orphan");

        // When
        service.deleteQuietly(new StoredDocumentFile(file.toString(), "hash", 6));

        // Then
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("删除临时文件 - 空路径时直接跳过")
    void testDeleteQuietly_BlankPath_Skip() {
        // Given
        RagProperties properties = new RagProperties();
        DocumentStorageService service = new DocumentStorageService(properties);

        // When
        service.deleteQuietly(new StoredDocumentFile("", "hash", 0));
        service.deleteQuietly(null);

        // Then
        assertThat(true).isTrue();
    }
}
