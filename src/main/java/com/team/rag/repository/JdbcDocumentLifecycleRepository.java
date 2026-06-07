package com.team.rag.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.bean.DocumentStatus;
import com.team.rag.bean.DocumentVersion;
import com.team.rag.bean.IngestionJob;
import com.team.rag.bean.IngestionStatus;
import com.team.rag.bean.RagDocument;
import com.team.rag.bean.StoredDocumentChunk;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC implementation for production document lifecycle persistence.
 */
@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.lifecycle", name = "repository", havingValue = "jdbc")
public class JdbcDocumentLifecycleRepository implements DocumentLifecycleRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(transactionManager = "ragLifecycleTransactionManager")
    public RagDocument saveDocument(RagDocument document) {
        if (findDocument(document.getId()).isPresent()) {
            jdbcTemplate.update("""
                            UPDATE rag_documents
                            SET filename = ?, doc_type = ?, project = ?, tags = ?, description = ?,
                                current_version = ?, status = ?, created_at = ?, updated_at = ?
                            WHERE id = ?
                            """,
                    document.getFilename(),
                    document.getDocType(),
                    document.getProject(),
                    document.getTags(),
                    document.getDescription(),
                    document.getCurrentVersion(),
                    name(document.getStatus()),
                    timestamp(document.getCreatedAt()),
                    timestamp(document.getUpdatedAt()),
                    document.getId());
        } else {
            jdbcTemplate.update("""
                            INSERT INTO rag_documents
                            (id, filename, doc_type, project, tags, description, current_version, status, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    document.getId(),
                    document.getFilename(),
                    document.getDocType(),
                    document.getProject(),
                    document.getTags(),
                    document.getDescription(),
                    document.getCurrentVersion(),
                    name(document.getStatus()),
                    timestamp(document.getCreatedAt()),
                    timestamp(document.getUpdatedAt()));
        }
        return document;
    }

    @Override
    public Optional<RagDocument> findDocument(String documentId) {
        return jdbcTemplate.query("""
                        SELECT id, filename, doc_type, project, tags, description, current_version, status, created_at, updated_at
                        FROM rag_documents
                        WHERE id = ?
                        """,
                documentMapper(),
                documentId).stream().findFirst();
    }

    @Override
    public List<RagDocument> findDocuments() {
        return jdbcTemplate.query("""
                        SELECT id, filename, doc_type, project, tags, description, current_version, status, created_at, updated_at
                        FROM rag_documents
                        ORDER BY updated_at DESC
                        """,
                documentMapper());
    }

    @Override
    public int nextVersion(String documentId) {
        Integer maxVersion = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM rag_document_versions WHERE document_id = ?",
                Integer.class,
                documentId);
        return (maxVersion == null ? 0 : maxVersion) + 1;
    }

    @Override
    @Transactional(transactionManager = "ragLifecycleTransactionManager")
    public DocumentVersion saveVersion(DocumentVersion version) {
        if (findVersion(version.getDocumentId(), version.getVersion()).isPresent()) {
            jdbcTemplate.update("""
                            UPDATE rag_document_versions
                            SET id = ?, file_hash = ?, file_path = ?, status = ?, segment_count = ?,
                                error_message = ?, created_at = ?, activated_at = ?
                            WHERE document_id = ? AND version = ?
                            """,
                    version.getId(),
                    version.getFileHash(),
                    version.getFilePath(),
                    name(version.getStatus()),
                    version.getSegmentCount(),
                    version.getErrorMessage(),
                    timestamp(version.getCreatedAt()),
                    timestamp(version.getActivatedAt()),
                    version.getDocumentId(),
                    version.getVersion());
        } else {
            jdbcTemplate.update("""
                            INSERT INTO rag_document_versions
                            (document_id, version, id, file_hash, file_path, status, segment_count, error_message, created_at, activated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    version.getDocumentId(),
                    version.getVersion(),
                    version.getId(),
                    version.getFileHash(),
                    version.getFilePath(),
                    name(version.getStatus()),
                    version.getSegmentCount(),
                    version.getErrorMessage(),
                    timestamp(version.getCreatedAt()),
                    timestamp(version.getActivatedAt()));
        }
        return version;
    }

    @Override
    public Optional<DocumentVersion> findVersion(String documentId, Integer version) {
        return jdbcTemplate.query("""
                        SELECT document_id, version, id, file_hash, file_path, status, segment_count, error_message, created_at, activated_at
                        FROM rag_document_versions
                        WHERE document_id = ? AND version = ?
                        """,
                versionMapper(),
                documentId,
                version).stream().findFirst();
    }

    @Override
    public List<DocumentVersion> findVersions(String documentId) {
        return jdbcTemplate.query("""
                        SELECT document_id, version, id, file_hash, file_path, status, segment_count, error_message, created_at, activated_at
                        FROM rag_document_versions
                        WHERE document_id = ?
                        ORDER BY version DESC
                        """,
                versionMapper(),
                documentId);
    }

    @Override
    @Transactional(transactionManager = "ragLifecycleTransactionManager")
    public IngestionJob saveJob(IngestionJob job) {
        if (findJob(job.getId()).isPresent()) {
            jdbcTemplate.update("""
                            UPDATE rag_ingestion_jobs
                            SET document_id = ?, version = ?, status = ?, progress = ?, error_message = ?,
                                retry_count = ?, created_at = ?, updated_at = ?
                            WHERE id = ?
                            """,
                    job.getDocumentId(),
                    job.getVersion(),
                    name(job.getStatus()),
                    job.getProgress(),
                    job.getErrorMessage(),
                    job.getRetryCount(),
                    timestamp(job.getCreatedAt()),
                    timestamp(job.getUpdatedAt()),
                    job.getId());
        } else {
            jdbcTemplate.update("""
                            INSERT INTO rag_ingestion_jobs
                            (id, document_id, version, status, progress, error_message, retry_count, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    job.getId(),
                    job.getDocumentId(),
                    job.getVersion(),
                    name(job.getStatus()),
                    job.getProgress(),
                    job.getErrorMessage(),
                    job.getRetryCount(),
                    timestamp(job.getCreatedAt()),
                    timestamp(job.getUpdatedAt()));
        }
        return job;
    }

    @Override
    public Optional<IngestionJob> findJob(String jobId) {
        return jdbcTemplate.query("""
                        SELECT id, document_id, version, status, progress, error_message, retry_count, created_at, updated_at
                        FROM rag_ingestion_jobs
                        WHERE id = ?
                        """,
                jobMapper(),
                jobId).stream().findFirst();
    }

    @Override
    public List<IngestionJob> findJobs(String documentId) {
        return jdbcTemplate.query("""
                        SELECT id, document_id, version, status, progress, error_message, retry_count, created_at, updated_at
                        FROM rag_ingestion_jobs
                        WHERE document_id = ?
                        ORDER BY created_at DESC
                        """,
                jobMapper(),
                documentId);
    }

    @Override
    public List<IngestionJob> findRecoverableJobs(int limit) {
        return jdbcTemplate.query("""
                        SELECT id, document_id, version, status, progress, error_message, retry_count, created_at, updated_at
                        FROM rag_ingestion_jobs
                        WHERE status IN (?, ?)
                        ORDER BY updated_at ASC, created_at ASC
                        LIMIT ?
                        """,
                jobMapper(),
                IngestionStatus.PENDING.name(),
                IngestionStatus.RUNNING.name(),
                Math.max(0, limit));
    }

    @Override
    @Transactional(transactionManager = "ragLifecycleTransactionManager")
    public void replaceChunks(String documentId, Integer version, List<StoredDocumentChunk> chunks) {
        jdbcTemplate.update("DELETE FROM rag_document_chunks WHERE document_id = ? AND version = ?", documentId, version);
        for (StoredDocumentChunk chunk : chunks) {
            jdbcTemplate.update("""
                            INSERT INTO rag_document_chunks
                            (id, document_id, version, chunk_index, vector_id, text, metadata_json, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    chunk.getId(),
                    chunk.getDocumentId(),
                    chunk.getVersion(),
                    chunk.getChunkIndex(),
                    chunk.getVectorId(),
                    chunk.getText(),
                    json(chunk.getMetadata()),
                    timestamp(chunk.getCreatedAt()));
        }
    }

    @Override
    public List<StoredDocumentChunk> findChunks(String documentId, Integer version) {
        return jdbcTemplate.query("""
                        SELECT id, document_id, version, chunk_index, vector_id, text, metadata_json, created_at
                        FROM rag_document_chunks
                        WHERE document_id = ? AND version = ?
                        ORDER BY chunk_index ASC
                        """,
                chunkMapper(),
                documentId,
                version);
    }

    @Override
    public void markIngestionStarted(String jobId) {
        int updated = jdbcTemplate.update("""
                        UPDATE rag_ingestion_jobs
                        SET status = ?, progress = ?, error_message = ?, retry_count = COALESCE(retry_count, 0) + 1, updated_at = ?
                        WHERE id = ?
                        """,
                IngestionStatus.RUNNING.name(),
                20,
                "",
                timestamp(OffsetDateTime.now()),
                jobId);
        requireUpdated(updated, "入库任务不存在：" + jobId);
    }

    @Override
    @Transactional(transactionManager = "ragLifecycleTransactionManager")
    public void markIngestionSucceeded(String jobId, Integer segmentCount) {
        OffsetDateTime now = OffsetDateTime.now();
        IngestionJob job = requireJob(jobId);
        jdbcTemplate.update("""
                        UPDATE rag_ingestion_jobs
                        SET status = ?, progress = ?, updated_at = ?
                        WHERE id = ?
                        """,
                IngestionStatus.SUCCESS.name(),
                100,
                timestamp(now),
                jobId);

        requireUpdated(jdbcTemplate.update("""
                        UPDATE rag_document_versions
                        SET status = ?, segment_count = ?, error_message = ?, activated_at = ?
                        WHERE document_id = ? AND version = ?
                        """,
                DocumentStatus.ACTIVE.name(),
                segmentCount,
                "",
                timestamp(now),
                job.getDocumentId(),
                job.getVersion()), "文档版本不存在：" + job.getDocumentId() + "#" + job.getVersion());

        jdbcTemplate.update("""
                        UPDATE rag_document_versions
                        SET status = ?
                        WHERE document_id = ? AND version <> ? AND status = ?
                        """,
                DocumentStatus.SUPERSEDED.name(),
                job.getDocumentId(),
                job.getVersion(),
                DocumentStatus.ACTIVE.name());

        jdbcTemplate.update("""
                        UPDATE rag_documents
                        SET current_version = ?, status = ?, updated_at = ?
                        WHERE id = ?
                        """,
                job.getVersion(),
                DocumentStatus.ACTIVE.name(),
                timestamp(now),
                job.getDocumentId());
    }

    @Override
    @Transactional(transactionManager = "ragLifecycleTransactionManager")
    public void markIngestionFailed(String jobId, String errorMessage) {
        OffsetDateTime now = OffsetDateTime.now();
        IngestionJob job = requireJob(jobId);
        requireUpdated(jdbcTemplate.update("""
                        UPDATE rag_ingestion_jobs
                        SET status = ?, progress = ?, error_message = ?, updated_at = ?
                        WHERE id = ?
                        """,
                IngestionStatus.FAILED.name(),
                100,
                errorMessage,
                timestamp(now),
                jobId), "入库任务不存在：" + jobId);

        requireUpdated(jdbcTemplate.update("""
                        UPDATE rag_document_versions
                        SET status = ?, error_message = ?
                        WHERE document_id = ? AND version = ?
                        """,
                DocumentStatus.FAILED.name(),
                errorMessage,
                job.getDocumentId(),
                job.getVersion()), "文档版本不存在：" + job.getDocumentId() + "#" + job.getVersion());

        RagDocument document = findDocument(job.getDocumentId()).orElse(null);
        if (document != null) {
            jdbcTemplate.update("""
                            UPDATE rag_documents
                            SET status = ?, updated_at = ?
                            WHERE id = ?
                            """,
                    document.getCurrentVersion() == null ? DocumentStatus.FAILED.name() : DocumentStatus.ACTIVE.name(),
                    timestamp(now),
                    document.getId());
        }
    }

    private IngestionJob requireJob(String jobId) {
        return findJob(jobId).orElseThrow(() -> new IllegalArgumentException("入库任务不存在：" + jobId));
    }

    private void requireUpdated(int updatedRows, String message) {
        if (updatedRows == 0) {
            throw new IllegalArgumentException(message);
        }
    }

    private RowMapper<RagDocument> documentMapper() {
        return (rs, rowNum) -> RagDocument.builder()
                .id(rs.getString("id"))
                .filename(rs.getString("filename"))
                .docType(rs.getString("doc_type"))
                .project(rs.getString("project"))
                .tags(rs.getString("tags"))
                .description(rs.getString("description"))
                .currentVersion(integer(rs, "current_version"))
                .status(documentStatus(rs.getString("status")))
                .createdAt(offsetDateTime(rs, "created_at"))
                .updatedAt(offsetDateTime(rs, "updated_at"))
                .build();
    }

    private RowMapper<DocumentVersion> versionMapper() {
        return (rs, rowNum) -> DocumentVersion.builder()
                .id(rs.getString("id"))
                .documentId(rs.getString("document_id"))
                .version(integer(rs, "version"))
                .fileHash(rs.getString("file_hash"))
                .filePath(rs.getString("file_path"))
                .status(documentStatus(rs.getString("status")))
                .segmentCount(integer(rs, "segment_count"))
                .errorMessage(rs.getString("error_message"))
                .createdAt(offsetDateTime(rs, "created_at"))
                .activatedAt(offsetDateTime(rs, "activated_at"))
                .build();
    }

    private RowMapper<IngestionJob> jobMapper() {
        return (rs, rowNum) -> IngestionJob.builder()
                .id(rs.getString("id"))
                .documentId(rs.getString("document_id"))
                .version(integer(rs, "version"))
                .status(ingestionStatus(rs.getString("status")))
                .progress(integer(rs, "progress"))
                .errorMessage(rs.getString("error_message"))
                .retryCount(integer(rs, "retry_count"))
                .createdAt(offsetDateTime(rs, "created_at"))
                .updatedAt(offsetDateTime(rs, "updated_at"))
                .build();
    }

    private RowMapper<StoredDocumentChunk> chunkMapper() {
        return (rs, rowNum) -> StoredDocumentChunk.builder()
                .id(rs.getString("id"))
                .documentId(rs.getString("document_id"))
                .version(integer(rs, "version"))
                .chunkIndex(integer(rs, "chunk_index"))
                .vectorId(rs.getString("vector_id"))
                .text(rs.getString("text"))
                .metadata(map(rs.getString("metadata_json")))
                .createdAt(offsetDateTime(rs, "created_at"))
                .build();
    }

    private Timestamp timestamp(OffsetDateTime time) {
        return time == null ? null : Timestamp.from(time.toInstant());
    }

    private OffsetDateTime offsetDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : OffsetDateTime.ofInstant(timestamp.toInstant(), ZoneId.systemDefault());
    }

    private Integer integer(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).intValue();
    }

    private String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private DocumentStatus documentStatus(String value) {
        return value == null ? null : DocumentStatus.valueOf(value);
    }

    private IngestionStatus ingestionStatus(String value) {
        return value == null ? null : IngestionStatus.valueOf(value);
    }

    private String json(Map<String, Object> metadata) {
        if (metadata == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("文档分块 metadata 序列化失败", ex);
        }
    }

    private Map<String, Object> map(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("文档分块 metadata 反序列化失败", ex);
        }
    }
}
