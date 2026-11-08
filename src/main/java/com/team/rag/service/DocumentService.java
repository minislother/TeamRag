package com.team.rag.service;

import com.team.rag.bean.DocumentForm;
import com.team.rag.bean.DocumentStatus;
import com.team.rag.bean.DocumentStatusResponse;
import com.team.rag.bean.DocumentUploadResponse;
import com.team.rag.bean.DocumentVersion;
import com.team.rag.bean.DocumentVersionResponse;
import com.team.rag.bean.IngestionJob;
import com.team.rag.bean.IngestionJobResponse;
import com.team.rag.bean.IngestionStatus;
import com.team.rag.bean.RagDocument;
import com.team.rag.config.RagProperties;
import com.team.rag.repository.DocumentLifecycleRepository;
import com.team.rag.security.AccessControlService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文档服务：负责接收上传、创建文档版本和异步入库任务。
 */
@Slf4j
@Service
public class DocumentService {

    private final DocumentStorageService storageService;
    private final DocumentLifecycleRepository documentRepository;
    private final DocumentIngestionWorker ingestionWorker;
    private final AccessControlService accessControlService;
    private final RagProperties properties;

    public DocumentService(DocumentStorageService storageService,
                           DocumentLifecycleRepository documentRepository,
                           DocumentIngestionWorker ingestionWorker,
                           AccessControlService accessControlService,
                           RagProperties properties) {
        this.storageService = storageService;
        this.documentRepository = documentRepository;
        this.ingestionWorker = ingestionWorker;
        this.accessControlService = accessControlService;
        this.properties = properties;
    }

    /**
     * 文档上传并提交后台入库任务。
     */
    public DocumentUploadResponse uploadAndStore(DocumentForm form) {
        MultipartFile file = form.getFile();
        validateFile(file);
        String originalFilename = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        String documentId = resolveDocumentId(form);
        RagDocument existing = documentRepository.findDocument(documentId).orElse(null);
        String project = resolveProject(form, existing);
        accessControlService.requireProjectAccess(project);
        int version = documentRepository.nextVersion(documentId);
        StoredDocumentFile storedFile = storageService.store(file, documentId, version);

        DocumentVersion unchangedVersion = findUnchangedActiveVersion(documentId, storedFile);
        if (unchangedVersion != null) {
            storageService.deleteQuietly(storedFile);
            return DocumentUploadResponse.builder()
                    .documentId(documentId)
                    .version(unchangedVersion.getVersion())
                    .filename(originalFilename)
                    .docType(resolveDocType(form, existing))
                    .project(resolveProject(form, existing))
                    .segmentCount(unchangedVersion.getSegmentCount())
                    .status(DocumentStatus.ACTIVE.name())
                    .message("文件内容未变化，已跳过重复入库")
                    .build();
        }

        RagDocument document = saveProcessingDocument(form, existing, documentId, originalFilename, project);
        DocumentVersion documentVersion = saveProcessingVersion(documentId, version, storedFile);
        IngestionJob job = savePendingJob(documentId, version);
        ingestionWorker.submit(job.getId());
        log.info("文档 {} 第 {} 版已提交后台入库任务 {}", originalFilename, version, job.getId());

        return DocumentUploadResponse.builder()
                .documentId(document.getId())
                .jobId(job.getId())
                .version(documentVersion.getVersion())
                .filename(document.getFilename())
                .docType(document.getDocType())
                .project(document.getProject())
                .segmentCount(0)
                .status(job.getStatus().name())
                .message("文档已提交入库任务，请稍后查看处理状态")
                .build();
    }

    /**
     * 查询所有文档的入库状态。
     */
    public List<DocumentStatusResponse> listDocuments() {
        return documentRepository.findDocuments().stream()
                .filter(document -> accessControlService.canAccessProject(document.getProject()))
                .sorted(Comparator.comparing(RagDocument::getUpdatedAt).reversed())
                .map(this::toStatusResponse)
                .toList();
    }

    /**
     * 查询单个文档的版本和任务状态。
     */
    public DocumentStatusResponse getDocument(String documentId) {
        RagDocument document = documentRepository.findDocument(documentId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在：" + documentId));
        accessControlService.requireProjectAccess(document.getProject());
        return toStatusResponse(document);
    }

    /**
     * 查询单个入库任务状态。
     */
    public IngestionJobResponse getJob(String jobId) {
        IngestionJob job = documentRepository.findJob(jobId)
                .orElseThrow(() -> new IllegalArgumentException("入库任务不存在：" + jobId));
        RagDocument document = documentRepository.findDocument(job.getDocumentId())
                .orElseThrow(() -> new IllegalArgumentException("文档不存在：" + job.getDocumentId()));
        accessControlService.requireProjectAccess(document.getProject());
        return toJobResponse(job);
    }

    /**
     * 校验上传文件是否存在且没有超过配置的大小限制。
     */
    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文档文件不能为空");
        }
        long maxBytes = properties.getDocument().getMaxUploadSizeMb() * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException("文档超过最大上传限制：" + properties.getDocument().getMaxUploadSizeMb() + "MB");
        }
    }

    private String resolveDocumentId(DocumentForm form) {
        if (StringUtils.hasText(form.getDocumentId())) {
            String documentId = form.getDocumentId().trim();
            documentRepository.findDocument(documentId)
                    .orElseThrow(() -> new IllegalArgumentException("文档不存在，无法上传新版本：" + documentId));
            return documentId;
        }
        return UUID.randomUUID().toString();
    }

    private RagDocument saveProcessingDocument(DocumentForm form, RagDocument existing, String documentId, String filename, String project) {
        OffsetDateTime now = OffsetDateTime.now();
        RagDocument document = RagDocument.builder()
                .id(documentId)
                .filename(filename)
                .docType(resolveDocType(form, existing))
                .project(project)
                .tags(resolveTags(form, existing))
                .description(resolveDescription(form, existing))
                .currentVersion(existing == null ? null : existing.getCurrentVersion())
                .status(DocumentStatus.PROCESSING)
                .createdAt(existing == null ? now : existing.getCreatedAt())
                .updatedAt(now)
                .build();
        return documentRepository.saveDocument(document);
    }

    private DocumentVersion saveProcessingVersion(String documentId, Integer version, StoredDocumentFile storedFile) {
        OffsetDateTime now = OffsetDateTime.now();
        return documentRepository.saveVersion(DocumentVersion.builder()
                .id(documentId + ":" + version)
                .documentId(documentId)
                .version(version)
                .fileHash(storedFile.fileHash())
                .filePath(storedFile.path())
                .status(DocumentStatus.PROCESSING)
                .segmentCount(0)
                .errorMessage("")
                .createdAt(now)
                .build());
    }

    private IngestionJob savePendingJob(String documentId, Integer version) {
        OffsetDateTime now = OffsetDateTime.now();
        return documentRepository.saveJob(IngestionJob.builder()
                .id(UUID.randomUUID().toString())
                .documentId(documentId)
                .version(version)
                .status(IngestionStatus.PENDING)
                .progress(0)
                .errorMessage("")
                .retryCount(0)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    /**
     * Hash is the fast path; when it matches, verify size and bytes before skipping ingestion.
     * This keeps SHA-256 collisions from being treated as duplicate content.
     */
    private DocumentVersion findUnchangedActiveVersion(String documentId, StoredDocumentFile storedFile) {
        return documentRepository.findVersions(documentId).stream()
                .filter(version -> version.getStatus() == DocumentStatus.ACTIVE)
                .filter(version -> storedFile.fileHash().equals(version.getFileHash()))
                .filter(version -> sameFileContent(storedFile.path(), version.getFilePath()))
                .findFirst()
                .orElse(null);
    }

    private boolean sameFileContent(String candidatePath, String existingPath) {
        if (!StringUtils.hasText(candidatePath) || !StringUtils.hasText(existingPath)) {
            // Older metadata may not contain a path; SHA-256 remains the available check.
            return true;
        }
        try {
            Path candidate = Path.of(candidatePath);
            Path existing = Path.of(existingPath);
            // Object-storage URIs (and legacy records whose local file was removed)
            // cannot be byte-compared here; retain the SHA-256 fast-path in that case.
            if (!Files.isRegularFile(candidate) || !Files.isRegularFile(existing)) {
                return true;
            }
            if (Files.size(candidate) != Files.size(existing)) {
                return false;
            }
            return Files.mismatch(candidate, existing) == -1L;
        } catch (IOException | RuntimeException ex) {
            log.warn("文件哈希相同但二次内容校验失败，将继续入库: {}", ex.getMessage());
            return false;
        }
    }

    private String resolveDocType(DocumentForm form, RagDocument existing) {
        return StringUtils.hasText(form.getDocType()) ? form.getDocType().trim() : existing == null ? "未分类" : existing.getDocType();
    }

    private String resolveProject(DocumentForm form, RagDocument existing) {
        return StringUtils.hasText(form.getProject()) ? form.getProject().trim() : existing == null ? "" : existing.getProject();
    }

    private String resolveTags(DocumentForm form, RagDocument existing) {
        return StringUtils.hasText(form.getTags()) ? form.getTags().trim() : existing == null ? "" : existing.getTags();
    }

    private String resolveDescription(DocumentForm form, RagDocument existing) {
        return StringUtils.hasText(form.getDescription()) ? form.getDescription().trim() : existing == null ? "" : existing.getDescription();
    }

    private DocumentStatusResponse toStatusResponse(RagDocument document) {
        return DocumentStatusResponse.builder()
                .documentId(document.getId())
                .filename(document.getFilename())
                .docType(document.getDocType())
                .project(document.getProject())
                .tags(document.getTags())
                .description(document.getDescription())
                .currentVersion(document.getCurrentVersion())
                .status(document.getStatus() == null ? "" : document.getStatus().name())
                .createdAt(text(document.getCreatedAt()))
                .updatedAt(text(document.getUpdatedAt()))
                .versions(documentRepository.findVersions(document.getId()).stream().map(this::toVersionResponse).toList())
                .jobs(documentRepository.findJobs(document.getId()).stream().map(this::toJobResponse).toList())
                .build();
    }

    private DocumentVersionResponse toVersionResponse(DocumentVersion version) {
        return DocumentVersionResponse.builder()
                .version(version.getVersion())
                .status(version.getStatus() == null ? "" : version.getStatus().name())
                .segmentCount(version.getSegmentCount())
                .fileHash(version.getFileHash())
                .errorMessage(version.getErrorMessage())
                .createdAt(text(version.getCreatedAt()))
                .activatedAt(text(version.getActivatedAt()))
                .build();
    }

    private IngestionJobResponse toJobResponse(IngestionJob job) {
        return IngestionJobResponse.builder()
                .jobId(job.getId())
                .documentId(job.getDocumentId())
                .version(job.getVersion())
                .status(job.getStatus() == null ? "" : job.getStatus().name())
                .progress(job.getProgress())
                .errorMessage(job.getErrorMessage())
                .retryCount(job.getRetryCount())
                .createdAt(text(job.getCreatedAt()))
                .updatedAt(text(job.getUpdatedAt()))
                .build();
    }

    private String text(OffsetDateTime time) {
        return time == null ? "" : time.toString();
    }
}
