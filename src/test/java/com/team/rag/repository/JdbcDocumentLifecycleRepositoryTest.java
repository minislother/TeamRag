package com.team.rag.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.bean.DocumentStatus;
import com.team.rag.bean.DocumentVersion;
import com.team.rag.bean.IngestionJob;
import com.team.rag.bean.IngestionStatus;
import com.team.rag.bean.RagDocument;
import com.team.rag.bean.StoredDocumentChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JDBC 文档生命周期仓储单元测试")
class JdbcDocumentLifecycleRepositoryTest {

    private JdbcDocumentLifecycleRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        createSchema(jdbcTemplate);
        repository = new JdbcDocumentLifecycleRepository(jdbcTemplate, new ObjectMapper());
    }

    @Test
    @DisplayName("保存并查询文档生命周期数据 - 成功")
    void testSaveAndFindLifecycleData_Success() {
        // Given
        OffsetDateTime now = OffsetDateTime.now();
        RagDocument document = RagDocument.builder()
                .id("doc-1")
                .filename("redis.txt")
                .docType("研发规范")
                .project("infra")
                .tags("Redis")
                .description("缓存规范")
                .status(DocumentStatus.PROCESSING)
                .createdAt(now)
                .updatedAt(now)
                .build();
        DocumentVersion version = DocumentVersion.builder()
                .id("doc-1:1")
                .documentId("doc-1")
                .version(1)
                .fileHash("hash-1")
                .filePath("s3://bucket/doc-1/1/redis.txt")
                .status(DocumentStatus.PROCESSING)
                .segmentCount(0)
                .errorMessage("")
                .createdAt(now)
                .build();
        IngestionJob job = IngestionJob.builder()
                .id("job-1")
                .documentId("doc-1")
                .version(1)
                .status(IngestionStatus.PENDING)
                .progress(0)
                .errorMessage("")
                .retryCount(0)
                .createdAt(now)
                .updatedAt(now)
                .build();

        // When
        repository.saveDocument(document);
        repository.saveVersion(version);
        repository.saveJob(job);
        repository.replaceChunks("doc-1", 1, List.of(StoredDocumentChunk.builder()
                .id("chunk-1")
                .documentId("doc-1")
                .version(1)
                .chunkIndex(0)
                .vectorId("vector-1")
                .text("Redis cache")
                .metadata(Map.of("project", "infra", "version", 1))
                .createdAt(now)
                .build()));

        // Then
        assertThat(repository.findDocument("doc-1")).isPresent();
        assertThat(repository.findDocuments()).extracting(RagDocument::getId).containsExactly("doc-1");
        assertThat(repository.findVersions("doc-1")).hasSize(1);
        assertThat(repository.findJobs("doc-1")).hasSize(1);
        assertThat(repository.findChunks("doc-1", 1)).hasSize(1);
        assertThat(repository.findChunks("doc-1", 1).get(0).getMetadata()).containsEntry("project", "infra");
        assertThat(repository.nextVersion("doc-1")).isEqualTo(2);
    }

    @Test
    @DisplayName("入库成功 - 激活新版本并替代旧版本")
    void testMarkIngestionSucceeded_ActivateAndSupersede() {
        // Given
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
        repository.saveJob(job("job-2", "doc-1", 2));

        // When
        repository.markIngestionSucceeded("job-2", 3);

        // Then
        assertThat(repository.findVersion("doc-1", 1).orElseThrow().getStatus()).isEqualTo(DocumentStatus.SUPERSEDED);
        assertThat(repository.findVersion("doc-1", 2).orElseThrow().getStatus()).isEqualTo(DocumentStatus.ACTIVE);
        assertThat(repository.findDocument("doc-1").orElseThrow().getCurrentVersion()).isEqualTo(2);
        assertThat(repository.findJob("job-2").orElseThrow().getStatus()).isEqualTo(IngestionStatus.SUCCESS);
    }

    @Test
    @DisplayName("入库失败 - 标记任务和版本失败")
    void testMarkIngestionFailed_UpdateJobAndVersion() {
        // Given
        repository.saveVersion(version("doc-1", 1, DocumentStatus.PROCESSING));
        repository.saveJob(job("job-1", "doc-1", 1));

        // When
        repository.markIngestionFailed("job-1", "解析失败");

        // Then
        IngestionJob job = repository.findJob("job-1").orElseThrow();
        DocumentVersion version = repository.findVersion("doc-1", 1).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(IngestionStatus.FAILED);
        assertThat(job.getErrorMessage()).isEqualTo("解析失败");
        assertThat(version.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(version.getErrorMessage()).isEqualTo("解析失败");
    }

    @Test
    @DisplayName("入库状态变更 - 任务不存在时抛出异常")
    void testMarkIngestionStarted_MissingJob_ThrowException() {
        // When & Then
        assertThatThrownBy(() -> repository.markIngestionStarted("missing-job"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing-job");
    }

    @Test
    @DisplayName("入库成功 - 任务不存在时抛出异常")
    void testMarkIngestionSucceeded_MissingJob_ThrowException() {
        // When & Then
        assertThatThrownBy(() -> repository.markIngestionSucceeded("missing-job", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing-job");
    }

    @Test
    @DisplayName("恢复任务查询 - 只返回 PENDING 和 RUNNING 并递增重试次数")
    void testFindRecoverableJobs_ReturnPendingAndRunningJobs() {
        // Given
        OffsetDateTime now = OffsetDateTime.now();
        repository.saveJob(job("job-running", "doc-1", 1, IngestionStatus.RUNNING, now.minusMinutes(2)));
        repository.saveJob(job("job-pending", "doc-1", 2, IngestionStatus.PENDING, now.minusMinutes(3)));
        repository.saveJob(job("job-success", "doc-1", 3, IngestionStatus.SUCCESS, now.minusMinutes(1)));
        repository.saveJob(job("job-failed", "doc-1", 4, IngestionStatus.FAILED, now));

        // When
        List<IngestionJob> jobs = repository.findRecoverableJobs(10);

        // Then
        assertThat(jobs).extracting(IngestionJob::getId).containsExactly("job-pending", "job-running");

        // When
        repository.markIngestionStarted("job-pending");

        // Then
        IngestionJob started = repository.findJob("job-pending").orElseThrow();
        assertThat(started.getStatus()).isEqualTo(IngestionStatus.RUNNING);
        assertThat(started.getRetryCount()).isEqualTo(1);
        assertThat(started.getProgress()).isEqualTo(20);
    }

    private DocumentVersion version(String documentId, Integer version, DocumentStatus status) {
        return DocumentVersion.builder()
                .id(documentId + ":" + version)
                .documentId(documentId)
                .version(version)
                .fileHash("hash-" + version)
                .filePath("s3://bucket/" + documentId + "/" + version)
                .status(status)
                .segmentCount(0)
                .errorMessage("")
                .createdAt(OffsetDateTime.now())
                .build();
    }

    private IngestionJob job(String jobId, String documentId, Integer version) {
        return job(jobId, documentId, version, IngestionStatus.PENDING, OffsetDateTime.now());
    }

    private IngestionJob job(String jobId, String documentId, Integer version, IngestionStatus status, OffsetDateTime now) {
        return IngestionJob.builder()
                .id(jobId)
                .documentId(documentId)
                .version(version)
                .status(status)
                .progress(status == IngestionStatus.RUNNING ? 20 : 0)
                .retryCount(0)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private void createSchema(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
                CREATE TABLE rag_documents (
                    id VARCHAR(64) PRIMARY KEY,
                    filename VARCHAR(512) NOT NULL,
                    doc_type VARCHAR(128),
                    project VARCHAR(128),
                    tags VARCHAR(512),
                    description CLOB,
                    current_version INT,
                    status VARCHAR(32),
                    created_at TIMESTAMP NULL,
                    updated_at TIMESTAMP NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE rag_document_versions (
                    document_id VARCHAR(64) NOT NULL,
                    version INT NOT NULL,
                    id VARCHAR(128) NOT NULL,
                    file_hash VARCHAR(128),
                    file_path VARCHAR(1024),
                    status VARCHAR(32),
                    segment_count INT,
                    error_message CLOB,
                    created_at TIMESTAMP NULL,
                    activated_at TIMESTAMP NULL,
                    PRIMARY KEY (document_id, version)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE rag_ingestion_jobs (
                    id VARCHAR(64) PRIMARY KEY,
                    document_id VARCHAR(64) NOT NULL,
                    version INT NOT NULL,
                    status VARCHAR(32),
                    progress INT,
                    error_message CLOB,
                    retry_count INT,
                    created_at TIMESTAMP NULL,
                    updated_at TIMESTAMP NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE rag_document_chunks (
                    id VARCHAR(160) PRIMARY KEY,
                    document_id VARCHAR(64) NOT NULL,
                    version INT NOT NULL,
                    chunk_index INT NOT NULL,
                    vector_id VARCHAR(160),
                    text CLOB,
                    metadata_json CLOB,
                    created_at TIMESTAMP NULL
                )
                """);
    }
}
