package com.team.rag.repository;

import com.team.rag.bean.DocumentStatus;
import com.team.rag.bean.DocumentVersion;
import com.team.rag.bean.IngestionJob;
import com.team.rag.bean.IngestionStatus;
import com.team.rag.bean.RagDocument;
import com.team.rag.bean.StoredDocumentChunk;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 面向本地运行的内存文档生命周期仓储。
 */
@Repository
public class InMemoryDocumentLifecycleRepository implements DocumentLifecycleRepository {

    private final Map<String, RagDocument> documents = new LinkedHashMap<>();
    private final Map<String, DocumentVersion> versions = new LinkedHashMap<>();
    private final Map<String, IngestionJob> jobs = new LinkedHashMap<>();
    private final Map<String, List<StoredDocumentChunk>> chunks = new LinkedHashMap<>();

    @Override
    public synchronized RagDocument saveDocument(RagDocument document) {
        documents.put(document.getId(), document);
        return document;
    }

    @Override
    public synchronized Optional<RagDocument> findDocument(String documentId) {
        return Optional.ofNullable(documents.get(documentId));
    }

    @Override
    public synchronized List<RagDocument> findDocuments() {
        return new ArrayList<>(documents.values());
    }

    @Override
    public synchronized int nextVersion(String documentId) {
        return versions.values().stream()
                .filter(version -> documentId.equals(version.getDocumentId()))
                .map(DocumentVersion::getVersion)
                .max(Integer::compareTo)
                .orElse(0) + 1;
    }

    @Override
    public synchronized DocumentVersion saveVersion(DocumentVersion version) {
        versions.put(versionKey(version.getDocumentId(), version.getVersion()), version);
        return version;
    }

    @Override
    public synchronized Optional<DocumentVersion> findVersion(String documentId, Integer version) {
        return Optional.ofNullable(versions.get(versionKey(documentId, version)));
    }

    @Override
    public synchronized List<DocumentVersion> findVersions(String documentId) {
        return versions.values().stream()
                .filter(version -> documentId.equals(version.getDocumentId()))
                .sorted(Comparator.comparing(DocumentVersion::getVersion).reversed())
                .toList();
    }

    @Override
    public synchronized IngestionJob saveJob(IngestionJob job) {
        jobs.put(job.getId(), job);
        return job;
    }

    @Override
    public synchronized Optional<IngestionJob> findJob(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    @Override
    public synchronized List<IngestionJob> findJobs(String documentId) {
        return jobs.values().stream()
                .filter(job -> documentId.equals(job.getDocumentId()))
                .sorted(Comparator.comparing(IngestionJob::getCreatedAt).reversed())
                .toList();
    }

    @Override
    public synchronized void replaceChunks(String documentId, Integer version, List<StoredDocumentChunk> newChunks) {
        chunks.put(versionKey(documentId, version), new ArrayList<>(newChunks));
    }

    @Override
    public synchronized List<StoredDocumentChunk> findChunks(String documentId, Integer version) {
        return new ArrayList<>(chunks.getOrDefault(versionKey(documentId, version), List.of()));
    }

    @Override
    public synchronized void markIngestionStarted(String jobId) {
        IngestionJob job = requireJob(jobId);
        job.setStatus(IngestionStatus.RUNNING);
        job.setProgress(20);
        job.setUpdatedAt(OffsetDateTime.now());
    }

    @Override
    public synchronized void markIngestionSucceeded(String jobId, Integer segmentCount) {
        OffsetDateTime now = OffsetDateTime.now();
        IngestionJob job = requireJob(jobId);
        job.setStatus(IngestionStatus.SUCCESS);
        job.setProgress(100);
        job.setUpdatedAt(now);

        DocumentVersion target = requireVersion(job.getDocumentId(), job.getVersion());
        target.setStatus(DocumentStatus.ACTIVE);
        target.setSegmentCount(segmentCount);
        target.setErrorMessage("");
        target.setActivatedAt(now);

        versions.values().stream()
                .filter(version -> job.getDocumentId().equals(version.getDocumentId()))
                .filter(version -> !job.getVersion().equals(version.getVersion()))
                .filter(version -> version.getStatus() == DocumentStatus.ACTIVE)
                .forEach(version -> version.setStatus(DocumentStatus.SUPERSEDED));

        RagDocument document = documents.get(job.getDocumentId());
        if (document != null) {
            document.setCurrentVersion(job.getVersion());
            document.setStatus(DocumentStatus.ACTIVE);
            document.setUpdatedAt(now);
        }
    }

    @Override
    public synchronized void markIngestionFailed(String jobId, String errorMessage) {
        OffsetDateTime now = OffsetDateTime.now();
        IngestionJob job = requireJob(jobId);
        job.setStatus(IngestionStatus.FAILED);
        job.setProgress(100);
        job.setErrorMessage(errorMessage);
        job.setUpdatedAt(now);

        DocumentVersion version = requireVersion(job.getDocumentId(), job.getVersion());
        version.setStatus(DocumentStatus.FAILED);
        version.setErrorMessage(errorMessage);

        RagDocument document = documents.get(job.getDocumentId());
        if (document != null) {
            document.setStatus(document.getCurrentVersion() == null ? DocumentStatus.FAILED : DocumentStatus.ACTIVE);
            document.setUpdatedAt(now);
        }
    }

    private IngestionJob requireJob(String jobId) {
        IngestionJob job = jobs.get(jobId);
        if (job == null) {
            throw new IllegalArgumentException("入库任务不存在：" + jobId);
        }
        return job;
    }

    private DocumentVersion requireVersion(String documentId, Integer version) {
        DocumentVersion documentVersion = versions.get(versionKey(documentId, version));
        if (documentVersion == null) {
            throw new IllegalArgumentException("文档版本不存在：" + documentId + "#" + version);
        }
        return documentVersion;
    }

    private String versionKey(String documentId, Integer version) {
        return documentId + ":" + version;
    }
}
