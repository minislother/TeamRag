package com.team.rag.repository;

import com.team.rag.bean.DocumentVersion;
import com.team.rag.bean.IngestionJob;
import com.team.rag.bean.RagDocument;
import com.team.rag.bean.StoredDocumentChunk;

import java.util.List;
import java.util.Optional;

/**
 * 文档生命周期仓储接口。
 *
 * <p>当前项目先用内存实现保证本地易运行；生产环境可以替换为 MySQL / PostgreSQL 实现。</p>
 */
public interface DocumentLifecycleRepository {

    RagDocument saveDocument(RagDocument document);

    Optional<RagDocument> findDocument(String documentId);

    List<RagDocument> findDocuments();

    int nextVersion(String documentId);

    DocumentVersion saveVersion(DocumentVersion version);

    Optional<DocumentVersion> findVersion(String documentId, Integer version);

    List<DocumentVersion> findVersions(String documentId);

    IngestionJob saveJob(IngestionJob job);

    Optional<IngestionJob> findJob(String jobId);

    List<IngestionJob> findJobs(String documentId);

    void replaceChunks(String documentId, Integer version, List<StoredDocumentChunk> chunks);

    List<StoredDocumentChunk> findChunks(String documentId, Integer version);

    void markIngestionStarted(String jobId);

    void markIngestionSucceeded(String jobId, Integer segmentCount);

    void markIngestionFailed(String jobId, String errorMessage);
}
