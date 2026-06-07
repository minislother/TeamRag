package com.team.rag.service;

import com.team.rag.bean.DocumentForm;
import com.team.rag.bean.DocumentStatus;
import com.team.rag.bean.DocumentStatusResponse;
import com.team.rag.bean.DocumentUploadResponse;
import com.team.rag.bean.DocumentVersion;
import com.team.rag.bean.IngestionJob;
import com.team.rag.bean.IngestionJobResponse;
import com.team.rag.bean.IngestionStatus;
import com.team.rag.bean.RagDocument;
import com.team.rag.config.RagProperties;
import com.team.rag.repository.DocumentLifecycleRepository;
import com.team.rag.repository.InMemoryDocumentLifecycleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("文档服务单元测试")
@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private DocumentStorageService storageService;
    @Mock
    private DocumentIngestionService ingestionService;

    private DocumentLifecycleRepository repository;
    private RagProperties properties;

    @BeforeEach
    void setUp() {
        repository = new InMemoryDocumentLifecycleRepository();
        properties = new RagProperties();
    }

    @Test
    @DisplayName("文档上传 - 空文件抛出异常")
    void testUploadAndStore_EmptyFile_ThrowException() {
        // Given
        DocumentService service = newService();
        DocumentForm form = new DocumentForm();
        form.setFile(new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]));

        // When & Then
        assertThatThrownBy(() -> service.uploadAndStore(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文档文件不能为空");
    }

    @Test
    @DisplayName("文档上传 - 文件为 null 抛出异常")
    void testUploadAndStore_NullFile_ThrowException() {
        // Given
        DocumentService service = newService();
        DocumentForm form = new DocumentForm();

        // When & Then
        assertThatThrownBy(() -> service.uploadAndStore(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文档文件不能为空");
    }

    @Test
    @DisplayName("文档上传 - 文件超过大小限制抛出异常")
    void testUploadAndStore_FileTooLarge_ThrowException() {
        // Given
        properties.getDocument().setMaxUploadSizeMb(0);
        DocumentService service = newService();
        DocumentForm form = new DocumentForm();
        form.setFile(new MockMultipartFile("file", "redis.txt", "text/plain", "Redis".getBytes()));

        // When & Then
        assertThatThrownBy(() -> service.uploadAndStore(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文档超过最大上传限制");
    }

    @Test
    @DisplayName("文档上传 - 新文档创建版本和入库任务")
    void testUploadAndStore_NewDocument_CreateJob() {
        // Given
        DocumentService service = newService();
        DocumentForm form = new DocumentForm();
        form.setFile(new MockMultipartFile("file", "redis.txt", "text/plain", "Redis 文档".getBytes()));
        form.setDocType("研发规范");
        form.setProject("infra");
        form.setTags("Redis,缓存");
        when(storageService.store(eq(form.getFile()), anyString(), eq(1)))
                .thenReturn(new StoredDocumentFile("D:/tmp/redis.txt", "hash-1", form.getFile().getSize()));

        // When
        DocumentUploadResponse response = service.uploadAndStore(form);

        // Then
        assertThat(response.getDocumentId()).isNotBlank();
        assertThat(response.getJobId()).isNotBlank();
        assertThat(response.getVersion()).isEqualTo(1);
        assertThat(response.getStatus()).isEqualTo("PENDING");
        assertThat(repository.findDocument(response.getDocumentId())).isPresent();
        assertThat(repository.findVersion(response.getDocumentId(), 1)).isPresent();
        assertThat(repository.findJob(response.getJobId())).isPresent();
        verify(ingestionService).ingest(response.getJobId());
    }

    @Test
    @DisplayName("文档上传 - 更新不存在的文档抛出异常")
    void testUploadAndStore_UpdateMissingDocument_ThrowException() {
        // Given
        DocumentService service = newService();
        DocumentForm form = new DocumentForm();
        form.setDocumentId("missing-doc");
        form.setFile(new MockMultipartFile("file", "redis.txt", "text/plain", "Redis 文档".getBytes()));

        // When & Then
        assertThatThrownBy(() -> service.uploadAndStore(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文档不存在");
    }

    @Test
    @DisplayName("文档上传 - 文件内容未变化时跳过重复入库")
    void testUploadAndStore_SameHash_SkipIngestion() {
        // Given
        DocumentService service = newService();
        OffsetDateTime now = OffsetDateTime.now();
        RagDocument document = RagDocument.builder()
                .id("doc-1")
                .filename("redis.txt")
                .docType("研发规范")
                .project("infra")
                .currentVersion(1)
                .status(DocumentStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();
        repository.saveDocument(document);
        repository.saveVersion(DocumentVersion.builder()
                .id("doc-1:1")
                .documentId("doc-1")
                .version(1)
                .fileHash("same-hash")
                .status(DocumentStatus.ACTIVE)
                .segmentCount(3)
                .createdAt(now)
                .activatedAt(now)
                .build());
        DocumentForm form = new DocumentForm();
        form.setDocumentId("doc-1");
        form.setFile(new MockMultipartFile("file", "redis.txt", "text/plain", "Redis 文档".getBytes()));
        when(storageService.store(eq(form.getFile()), eq("doc-1"), eq(2)))
                .thenReturn(new StoredDocumentFile("D:/tmp/redis.txt", "same-hash", form.getFile().getSize()));

        // When
        DocumentUploadResponse response = service.uploadAndStore(form);

        // Then
        assertThat(response.getStatus()).isEqualTo("ACTIVE");
        assertThat(response.getSegmentCount()).isEqualTo(3);
        verify(storageService).deleteQuietly(new StoredDocumentFile("D:/tmp/redis.txt", "same-hash", form.getFile().getSize()));
        verify(ingestionService, never()).ingest(anyString());
    }

    @Test
    @DisplayName("文档上传 - 已有文档新版本沿用旧元数据")
    void testUploadAndStore_UpdateExistingDocument_ReuseMetadata() {
        // Given
        DocumentService service = newService();
        OffsetDateTime now = OffsetDateTime.now();
        repository.saveDocument(RagDocument.builder()
                .id("doc-1")
                .filename("old.txt")
                .docType("研发规范")
                .project("infra")
                .tags("Redis,Cache")
                .description("旧版本说明")
                .currentVersion(1)
                .status(DocumentStatus.ACTIVE)
                .createdAt(now.minusDays(1))
                .updatedAt(now.minusHours(1))
                .build());
        repository.saveVersion(DocumentVersion.builder()
                .id("doc-1:1")
                .documentId("doc-1")
                .version(1)
                .fileHash("old-hash")
                .status(DocumentStatus.ACTIVE)
                .segmentCount(1)
                .createdAt(now.minusDays(1))
                .activatedAt(now.minusDays(1))
                .build());
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(10L);
        when(file.getOriginalFilename()).thenReturn(null);
        DocumentForm form = new DocumentForm();
        form.setDocumentId("doc-1");
        form.setFile(file);
        when(storageService.store(eq(file), eq("doc-1"), eq(2)))
                .thenReturn(new StoredDocumentFile("D:/tmp/new.txt", "new-hash", 10L));

        // When
        DocumentUploadResponse response = service.uploadAndStore(form);
        RagDocument saved = repository.findDocument("doc-1").orElseThrow();

        // Then
        assertThat(response.getFilename()).isEqualTo("unknown");
        assertThat(saved.getDocType()).isEqualTo("研发规范");
        assertThat(saved.getProject()).isEqualTo("infra");
        assertThat(saved.getTags()).isEqualTo("Redis,Cache");
        assertThat(saved.getDescription()).isEqualTo("旧版本说明");
        assertThat(saved.getCurrentVersion()).isEqualTo(1);
        verify(ingestionService).ingest(response.getJobId());
    }

    @Test
    @DisplayName("文档状态查询 - 返回文档版本和入库任务")
    void testStatusQueries_ReturnDocumentVersionsAndJobs() {
        // Given
        DocumentService service = newService();
        OffsetDateTime older = OffsetDateTime.now().minusMinutes(10);
        OffsetDateTime newer = OffsetDateTime.now();
        repository.saveDocument(RagDocument.builder()
                .id("doc-1")
                .filename("redis.txt")
                .docType("研发规范")
                .project("infra")
                .tags("Redis")
                .description("缓存规范")
                .currentVersion(1)
                .status(DocumentStatus.ACTIVE)
                .createdAt(older)
                .updatedAt(older)
                .build());
        repository.saveDocument(RagDocument.builder()
                .id("doc-2")
                .filename("mq.txt")
                .docType("项目资料")
                .project("message")
                .status(DocumentStatus.PROCESSING)
                .createdAt(newer)
                .updatedAt(newer)
                .build());
        repository.saveVersion(DocumentVersion.builder()
                .id("doc-1:1")
                .documentId("doc-1")
                .version(1)
                .fileHash("hash-1")
                .status(DocumentStatus.ACTIVE)
                .segmentCount(2)
                .errorMessage("")
                .createdAt(older)
                .activatedAt(newer)
                .build());
        repository.saveJob(IngestionJob.builder()
                .id("job-1")
                .documentId("doc-1")
                .version(1)
                .status(IngestionStatus.SUCCESS)
                .progress(100)
                .errorMessage("")
                .retryCount(0)
                .createdAt(older)
                .updatedAt(newer)
                .build());

        // When
        List<DocumentStatusResponse> documents = service.listDocuments();
        DocumentStatusResponse detail = service.getDocument("doc-1");
        IngestionJobResponse job = service.getJob("job-1");

        // Then
        assertThat(documents).extracting(DocumentStatusResponse::getDocumentId)
                .containsExactly("doc-2", "doc-1");
        assertThat(detail.getVersions()).hasSize(1);
        assertThat(detail.getJobs()).hasSize(1);
        assertThat(detail.getStatus()).isEqualTo("ACTIVE");
        assertThat(job.getStatus()).isEqualTo("SUCCESS");
        assertThat(job.getProgress()).isEqualTo(100);
    }

    @Test
    @DisplayName("文档状态查询 - 文档或任务不存在时抛出异常")
    void testStatusQueries_NotFound_ThrowException() {
        // Given
        DocumentService service = newService();

        // When & Then
        assertThatThrownBy(() -> service.getDocument("missing-doc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing-doc");
        assertThatThrownBy(() -> service.getJob("missing-job"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing-job");
    }

    @Test
    @DisplayName("文档状态查询 - 空状态和空时间返回空字符串")
    void testGetDocument_NullStatusAndTimes_ReturnEmptyText() {
        // Given
        DocumentService service = newService();
        repository.saveDocument(RagDocument.builder()
                .id("doc-null")
                .filename("null.txt")
                .status(null)
                .createdAt(null)
                .updatedAt(null)
                .build());
        repository.saveVersion(DocumentVersion.builder()
                .id("doc-null:1")
                .documentId("doc-null")
                .version(1)
                .status(null)
                .segmentCount(0)
                .createdAt(null)
                .activatedAt(null)
                .build());
        repository.saveJob(IngestionJob.builder()
                .id("job-null")
                .documentId("doc-null")
                .version(1)
                .status(null)
                .progress(0)
                .retryCount(0)
                .createdAt(null)
                .updatedAt(null)
                .build());

        // When
        DocumentStatusResponse response = service.getDocument("doc-null");

        // Then
        assertThat(response.getStatus()).isEmpty();
        assertThat(response.getCreatedAt()).isEmpty();
        assertThat(response.getUpdatedAt()).isEmpty();
        assertThat(response.getVersions().get(0).getStatus()).isEmpty();
        assertThat(response.getVersions().get(0).getCreatedAt()).isEmpty();
        assertThat(response.getJobs().get(0).getStatus()).isEmpty();
        assertThat(response.getJobs().get(0).getUpdatedAt()).isEmpty();
    }

    private DocumentService newService() {
        TaskExecutor directExecutor = Runnable::run;
        return new DocumentService(storageService, repository, ingestionService, directExecutor, properties);
    }
}
