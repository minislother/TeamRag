package com.team.rag.repository;

import com.team.rag.bean.DocumentStatus;
import com.team.rag.bean.DocumentVersion;
import com.team.rag.bean.IngestionJob;
import com.team.rag.bean.IngestionStatus;
import com.team.rag.bean.RagDocument;
import com.team.rag.bean.StoredDocumentChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("内存文档生命周期仓储单元测试")
class InMemoryDocumentLifecycleRepositoryTest {

    @Test
    @DisplayName("查询文档和任务 - 返回隔离副本并按任务创建时间倒序")
    void testFindDocumentsAndJobs_ReturnCopiesAndSortedJobs() {
        // Given
        InMemoryDocumentLifecycleRepository repository = new InMemoryDocumentLifecycleRepository();
        OffsetDateTime older = OffsetDateTime.now().minusMinutes(5);
        OffsetDateTime newer = OffsetDateTime.now();
        repository.saveDocument(RagDocument.builder()
                .id("doc-1")
                .filename("redis.txt")
                .status(DocumentStatus.ACTIVE)
                .createdAt(older)
                .updatedAt(older)
                .build());
        repository.saveDocument(RagDocument.builder()
                .id("doc-2")
                .filename("mq.txt")
                .status(DocumentStatus.ACTIVE)
                .createdAt(newer)
                .updatedAt(newer)
                .build());
        repository.saveJob(job("job-old", "doc-1", 1, older));
        repository.saveJob(job("job-new", "doc-1", 2, newer));
        repository.saveJob(job("job-other", "doc-2", 1, newer));

        // When
        List<RagDocument> documents = repository.findDocuments();
        List<IngestionJob> jobs = repository.findJobs("doc-1");

        // Then
        assertThat(documents).hasSize(2);
        documents.clear();
        assertThat(repository.findDocuments()).hasSize(2);
        assertThat(jobs).extracting(IngestionJob::getId).containsExactly("job-new", "job-old");
    }

    @Test
    @DisplayName("入库成功 - 激活新版本并把旧版本标记为已替代")
    void testMarkIngestionSucceeded_SupersedePreviousActiveVersion() {
        // Given
        InMemoryDocumentLifecycleRepository repository = new InMemoryDocumentLifecycleRepository();
        OffsetDateTime now = OffsetDateTime.now();
        repository.saveDocument(RagDocument.builder()
                .id("doc-1")
                .filename("redis.txt")
                .currentVersion(1)
                .status(DocumentStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build());
        repository.saveVersion(version("doc-1", 1, DocumentStatus.ACTIVE));
        repository.saveVersion(version("doc-1", 2, DocumentStatus.PROCESSING));
        repository.saveJob(job("job-2", "doc-1", 2, now));
        repository.replaceChunks("doc-1", 1, List.of(StoredDocumentChunk.builder()
                .id("chunk-1")
                .documentId("doc-1")
                .version(1)
                .chunkIndex(0)
                .vectorId("vector-1")
                .text("Redis")
                .metadata(Map.of())
                .createdAt(now)
                .build()));

        // When
        repository.markIngestionSucceeded("job-2", 4);

        // Then
        assertThat(repository.findVersion("doc-1", 1).orElseThrow().getStatus()).isEqualTo(DocumentStatus.SUPERSEDED);
        assertThat(repository.findVersion("doc-1", 2).orElseThrow().getStatus()).isEqualTo(DocumentStatus.ACTIVE);
        assertThat(repository.findDocument("doc-1").orElseThrow().getCurrentVersion()).isEqualTo(2);
        assertThat(repository.findJob("job-2").orElseThrow().getStatus()).isEqualTo(IngestionStatus.SUCCESS);
        assertThat(repository.findChunks("doc-1", 1)).hasSize(1);
    }

    @Test
    @DisplayName("入库成功 - 文档记录不存在时只激活版本和任务")
    void testMarkIngestionSucceeded_MissingDocument_OnlyActivateVersionAndJob() {
        // Given
        InMemoryDocumentLifecycleRepository repository = new InMemoryDocumentLifecycleRepository();
        OffsetDateTime now = OffsetDateTime.now();
        repository.saveVersion(version("doc-missing", 1, DocumentStatus.PROCESSING));
        repository.saveJob(job("job-1", "doc-missing", 1, now));

        // When
        repository.markIngestionSucceeded("job-1", 2);

        // Then
        assertThat(repository.findDocument("doc-missing")).isEmpty();
        assertThat(repository.findVersion("doc-missing", 1).orElseThrow().getStatus()).isEqualTo(DocumentStatus.ACTIVE);
        assertThat(repository.findJob("job-1").orElseThrow().getStatus()).isEqualTo(IngestionStatus.SUCCESS);
    }

    @Test
    @DisplayName("入库失败 - 有旧版本时文档保持可用")
    void testMarkIngestionFailed_ExistingActiveVersion_KeepDocumentActive() {
        // Given
        InMemoryDocumentLifecycleRepository repository = new InMemoryDocumentLifecycleRepository();
        OffsetDateTime now = OffsetDateTime.now();
        repository.saveDocument(RagDocument.builder()
                .id("doc-1")
                .filename("redis.txt")
                .currentVersion(1)
                .status(DocumentStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build());
        repository.saveVersion(version("doc-1", 1, DocumentStatus.ACTIVE));
        repository.saveVersion(version("doc-1", 2, DocumentStatus.PROCESSING));
        repository.saveJob(job("job-2", "doc-1", 2, now));

        // When
        repository.markIngestionFailed("job-2", "parse failed");

        // Then
        assertThat(repository.findDocument("doc-1").orElseThrow().getStatus()).isEqualTo(DocumentStatus.ACTIVE);
        assertThat(repository.findVersion("doc-1", 2).orElseThrow().getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(repository.findJob("job-2").orElseThrow().getStatus()).isEqualTo(IngestionStatus.FAILED);
    }

    @Test
    @DisplayName("入库状态变更 - 任务或版本缺失时抛出异常")
    void testStateChange_MissingJobOrVersion_ThrowException() {
        // Given
        InMemoryDocumentLifecycleRepository repository = new InMemoryDocumentLifecycleRepository();
        OffsetDateTime now = OffsetDateTime.now();
        repository.saveJob(job("job-1", "doc-1", 1, now));

        // When & Then
        assertThatThrownBy(() -> repository.markIngestionStarted("missing-job"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing-job");
        assertThatThrownBy(() -> repository.markIngestionSucceeded("job-1", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("doc-1#1");
    }

    private IngestionJob job(String jobId, String documentId, Integer version, OffsetDateTime createdAt) {
        return IngestionJob.builder()
                .id(jobId)
                .documentId(documentId)
                .version(version)
                .status(IngestionStatus.PENDING)
                .progress(0)
                .retryCount(0)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
    }

    private DocumentVersion version(String documentId, Integer version, DocumentStatus status) {
        return DocumentVersion.builder()
                .id(documentId + ":" + version)
                .documentId(documentId)
                .version(version)
                .fileHash("hash-" + version)
                .status(status)
                .segmentCount(0)
                .errorMessage("")
                .createdAt(OffsetDateTime.now())
                .build();
    }
}
