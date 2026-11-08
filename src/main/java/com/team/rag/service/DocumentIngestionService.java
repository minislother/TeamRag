package com.team.rag.service;

import com.team.rag.bean.DocumentStatus;
import com.team.rag.bean.DocumentSegment;
import com.team.rag.bean.DocumentVersion;
import com.team.rag.bean.IngestionJob;
import com.team.rag.bean.RagDocument;
import com.team.rag.bean.SparseVector;
import com.team.rag.bean.StoredDocumentChunk;
import com.team.rag.client.DashScopeClient;
import com.team.rag.client.PineconeClient;
import com.team.rag.config.RagProperties;
import com.team.rag.repository.DocumentLifecycleRepository;
import com.team.rag.util.BM25SparseEncoder;
import com.team.rag.util.SemanticSplitter;
import com.team.rag.util.RetrievalTextBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档后台入库服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestionService {

    private final Tika tika;
    private final SemanticSplitter semanticSplitter;
    private final DashScopeClient dashScopeClient;
    private final PineconeClient pineconeClient;
    private final BM25SparseEncoder sparseEncoder;
    private final RagProperties properties;
    private final DocumentLifecycleRepository documentRepository;
    private final DocumentStorageService storageService;

    /**
     * 执行指定入库任务，把文档新版本写入 Pinecone 并切换为 ACTIVE。
     */
    public void ingest(String jobId) {
        try {
            documentRepository.markIngestionStarted(jobId);
            IngestionJob job = documentRepository.findJob(jobId)
                    .orElseThrow(() -> new IllegalArgumentException("入库任务不存在：" + jobId));
            RagDocument document = documentRepository.findDocument(job.getDocumentId())
                    .orElseThrow(() -> new IllegalArgumentException("文档不存在：" + job.getDocumentId()));
            DocumentVersion version = documentRepository.findVersion(job.getDocumentId(), job.getVersion())
                    .orElseThrow(() -> new IllegalArgumentException("文档版本不存在：" + job.getDocumentId() + "#" + job.getVersion()));
            List<DocumentVersion> previousActiveVersions = activeVersionsBefore(job);

            String text = parse(version, document.getFilename());
            Map<String, Object> metadata = buildMetadata(document, version);
            List<DocumentSegment> segments = semanticSplitter.splitAdaptive(document.getId(), text, metadata);
            if (segments.isEmpty()) {
                throw new IllegalArgumentException("文档未生成有效语义块");
            }

            // 标题、文件名、类型和标签参与向量表达，正文仍保持原样用于回答展示。
            List<String> segmentTexts = segments.stream()
                    .map(segment -> RetrievalTextBuilder.build(segment.getText(), segment.getMetadata()))
                    .toList();
            List<float[]> embeddings = dashScopeClient.embed(segmentTexts);
            List<PineconeClient.VectorRecord> records = new ArrayList<>();
            List<StoredDocumentChunk> chunks = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                DocumentSegment segment = segments.get(i);
                SparseVector sparseVector = sparseEncoder.encode(
                        RetrievalTextBuilder.build(segment.getText(), segment.getMetadata()));
                records.add(new PineconeClient.VectorRecord(segment.getId(), embeddings.get(i), sparseVector, segment.getMetadata()));
                chunks.add(StoredDocumentChunk.builder()
                        .id(segment.getId())
                        .documentId(document.getId())
                        .version(version.getVersion())
                        .chunkIndex(i)
                        .vectorId(segment.getId())
                        .text(segment.getText())
                        .metadata(segment.getMetadata())
                        .createdAt(OffsetDateTime.now())
                        .build());
            }

            pineconeClient.upsert(properties.getPinecone().getNamespace(), records);
            documentRepository.replaceChunks(document.getId(), version.getVersion(), chunks);
            documentRepository.markIngestionSucceeded(jobId, chunks.size());
            log.info("文档 {} 第 {} 版入库成功，生成 {} 个语义块", document.getFilename(), version.getVersion(), chunks.size());
            deleteSupersededVectors(previousActiveVersions);
        } catch (Exception ex) {
            log.error("文档入库任务失败: {}", jobId, ex);
            documentRepository.findJob(jobId)
                    .ifPresent(job -> documentRepository.markIngestionFailed(jobId, ex.getMessage()));
        }
    }

    private String parse(DocumentVersion version, String filename) throws Exception {
        try (InputStream inputStream = storageService.open(version.getFilePath())) {
            String extension = filename == null ? "" : filename.toLowerCase(java.util.Locale.ROOT);
            String text = extension.matches(".*\\.(pdf|docx?|xlsx?|html?)$")
                    ? com.team.rag.util.StructuredDocumentParser.parse(inputStream, filename)
                    : tika.parseToString(inputStream);
            if (!StringUtils.hasText(text)) {
                throw new IllegalArgumentException("文档未解析出有效文本");
            }
            return text;
        }
    }

    private Map<String, Object> buildMetadata(RagDocument document, DocumentVersion version) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("documentId", document.getId());
        metadata.put("tenantId", properties.getSecurity().getTenantId());
        metadata.put("version", version.getVersion());
        metadata.put("status", DocumentStatus.ACTIVE.name());
        metadata.put("filename", document.getFilename());
        metadata.put("documentTitle", documentTitle(document.getFilename()));
        metadata.put("docType", document.getDocType());
        metadata.put("project", document.getProject());
        metadata.put("tags", document.getTags());
        metadata.put("description", document.getDescription());
        metadata.put("fileHash", version.getFileHash());
        metadata.put("uploadedAt", version.getCreatedAt().toString());
        metadata.put("updatedAt", (document.getUpdatedAt() == null ? version.getCreatedAt() : document.getUpdatedAt()).toString());
        // 权限标签来自已校验的项目归属，不能让上传者通过普通 tags 覆盖访问权限。
        metadata.put("permissionTags", List.of("project:" + document.getProject()));
        return metadata;
    }

    private String documentTitle(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "";
        }
        int extension = filename.lastIndexOf('.');
        return extension > 0 ? filename.substring(0, extension) : filename;
    }

    private List<DocumentVersion> activeVersionsBefore(IngestionJob job) {
        return documentRepository.findVersions(job.getDocumentId()).stream()
                .filter(version -> version.getStatus() == DocumentStatus.ACTIVE)
                .filter(version -> !job.getVersion().equals(version.getVersion()))
                .toList();
    }

    private void deleteSupersededVectors(List<DocumentVersion> previousActiveVersions) {
        for (DocumentVersion previousVersion : previousActiveVersions) {
            List<String> vectorIds = documentRepository.findChunks(previousVersion.getDocumentId(), previousVersion.getVersion()).stream()
                    .map(StoredDocumentChunk::getVectorId)
                    .filter(StringUtils::hasText)
                    .toList();
            if (vectorIds.isEmpty()) {
                continue;
            }
            try {
                pineconeClient.deleteByIds(properties.getPinecone().getNamespace(), vectorIds);
            } catch (Exception ex) {
                log.warn("旧版本向量清理失败，documentId={}, version={}", previousVersion.getDocumentId(), previousVersion.getVersion(), ex);
            }
        }
    }
}
