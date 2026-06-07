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
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("JDBC 生命周期仓储 MySQL 集成测试")
class JdbcDocumentLifecycleRepositoryIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0.36"))
            .withDatabaseName("team_rag_it")
            .withUsername("rag")
            .withPassword("rag-secret");

    private JdbcTemplate jdbcTemplate;
    private JdbcDocumentLifecycleRepository repository;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = dataSource();
        jdbcTemplate = new JdbcTemplate(dataSource);
        resetSchema(dataSource);
        repository = new JdbcDocumentLifecycleRepository(jdbcTemplate, new ObjectMapper());
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    @DisplayName("迁移脚本 - 真实 MySQL 创建表和索引")
    void testMigrationScript_CreateTablesAndIndexes() {
        // When
        List<String> documentIndexes = indexes("rag_documents");
        List<String> jobIndexes = indexes("rag_ingestion_jobs");
        List<String> chunkIndexes = indexes("rag_document_chunks");

        // Then
        assertThat(documentIndexes).contains("PRIMARY", "idx_rag_documents_project", "idx_rag_documents_status");
        assertThat(jobIndexes).contains("PRIMARY", "idx_rag_jobs_document_id", "idx_rag_jobs_status");
        assertThat(chunkIndexes).contains("PRIMARY", "idx_rag_chunks_doc_version", "idx_rag_chunks_vector_id");
    }

    @Test
    @DisplayName("事务回滚 - 保存文档后回滚不落库")
    void testTransactionRollback_SaveDocument_Rollback() {
        // Given
        RagDocument document = document("doc-rollback", "rollback.txt", DocumentStatus.PROCESSING);

        // When
        transactionTemplate.executeWithoutResult(status -> {
            repository.saveDocument(document);
            status.setRollbackOnly();
        });

        // Then
        assertThat(repository.findDocument("doc-rollback")).isEmpty();
    }

    @Test
    @DisplayName("版本入库 - 新版本成功后激活并替换旧版本")
    void testLifecycle_NewVersionSucceeded_ActivateAndSupersede() {
        // Given
        OffsetDateTime now = OffsetDateTime.now();
        RagDocument document = document("doc-1", "redis.txt", DocumentStatus.ACTIVE);
        document.setCurrentVersion(1);
        repository.saveDocument(document);
        repository.saveVersion(version("doc-1", 1, DocumentStatus.ACTIVE));
        repository.saveVersion(version("doc-1", 2, DocumentStatus.PROCESSING));
        repository.saveJob(job("job-2", "doc-1", 2, IngestionStatus.PENDING, now));
        repository.replaceChunks("doc-1", 2, List.of(StoredDocumentChunk.builder()
                .id("chunk-1")
                .documentId("doc-1")
                .version(2)
                .chunkIndex(0)
                .vectorId("vector-1")
                .text("Redis 缓存穿透")
                .metadata(Map.of("project", "infra", "version", 2))
                .createdAt(now)
                .build()));

        // When
        repository.markIngestionSucceeded("job-2", 1);

        // Then
        assertThat(repository.findDocument("doc-1").orElseThrow().getCurrentVersion()).isEqualTo(2);
        assertThat(repository.findVersion("doc-1", 1).orElseThrow().getStatus()).isEqualTo(DocumentStatus.SUPERSEDED);
        assertThat(repository.findVersion("doc-1", 2).orElseThrow().getStatus()).isEqualTo(DocumentStatus.ACTIVE);
        assertThat(repository.findJob("job-2").orElseThrow().getStatus()).isEqualTo(IngestionStatus.SUCCESS);
        assertThat(repository.findChunks("doc-1", 2).get(0).getMetadata()).containsEntry("project", "infra");
    }

    @Test
    @DisplayName("入库失败 - 真实 MySQL 标记任务和版本失败")
    void testLifecycle_IngestionFailed_MarkFailed() {
        // Given
        repository.saveVersion(version("doc-1", 1, DocumentStatus.PROCESSING));
        repository.saveJob(job("job-1", "doc-1", 1, IngestionStatus.PENDING, OffsetDateTime.now()));

        // When
        repository.markIngestionFailed("job-1", "解析失败");

        // Then
        assertThat(repository.findJob("job-1").orElseThrow().getStatus()).isEqualTo(IngestionStatus.FAILED);
        assertThat(repository.findVersion("doc-1", 1).orElseThrow().getStatus()).isEqualTo(DocumentStatus.FAILED);
    }

    private DriverManagerDataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(MYSQL.getDriverClassName());
        dataSource.setUrl(MYSQL.getJdbcUrl());
        dataSource.setUsername(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        return dataSource;
    }

    private void resetSchema(DataSource dataSource) throws Exception {
        jdbcTemplate.execute("DROP TABLE IF EXISTS rag_document_chunks");
        jdbcTemplate.execute("DROP TABLE IF EXISTS rag_ingestion_jobs");
        jdbcTemplate.execute("DROP TABLE IF EXISTS rag_document_versions");
        jdbcTemplate.execute("DROP TABLE IF EXISTS rag_documents");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/mysql/V1__document_lifecycle.sql"));
        }
    }

    private List<String> indexes(String tableName) {
        return jdbcTemplate.queryForList("""
                        SELECT INDEX_NAME
                        FROM information_schema.STATISTICS
                        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?
                        """,
                String.class,
                tableName);
    }

    private RagDocument document(String id, String filename, DocumentStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        return RagDocument.builder()
                .id(id)
                .filename(filename)
                .docType("研发规范")
                .project("infra")
                .tags("Redis")
                .description("缓存规范")
                .currentVersion(null)
                .status(status)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private DocumentVersion version(String documentId, Integer version, DocumentStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        return DocumentVersion.builder()
                .id(documentId + ":" + version)
                .documentId(documentId)
                .version(version)
                .fileHash("hash-" + version)
                .filePath("s3://bucket/" + documentId + "/" + version)
                .status(status)
                .segmentCount(0)
                .errorMessage("")
                .createdAt(now)
                .activatedAt(status == DocumentStatus.ACTIVE ? now : null)
                .build();
    }

    private IngestionJob job(String jobId, String documentId, Integer version, IngestionStatus status, OffsetDateTime now) {
        return IngestionJob.builder()
                .id(jobId)
                .documentId(documentId)
                .version(version)
                .status(status)
                .progress(0)
                .errorMessage("")
                .retryCount(0)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
