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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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

            String text = parse(version);
            Map<String, Object> metadata = buildMetadata(document, version);
            List<DocumentSegment> segments = semanticSplitter.split(document.getId(), text, metadata);
            if (segments.isEmpty()) {
                throw new IllegalArgumentException("文档未生成有效语义块");
            }

            List<String> segmentTexts = segments.stream().map(DocumentSegment::getText).toList();
            List<float[]> embeddings = dashScopeClient.embed(segmentTexts);
            List<PineconeClient.VectorRecord> records = new ArrayList<>();
            List<StoredDocumentChunk> chunks = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                DocumentSegment segment = segments.get(i);
                SparseVector sparseVector = sparseEncoder.encode(segment.getText());
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

    private String parse(DocumentVersion version) throws Exception {
        try (InputStream inputStream = Files.newInputStream(Path.of(version.getFilePath()))) {
            String text = tika.parseToString(inputStream);
            if (!StringUtils.hasText(text)) {
                throw new IllegalArgumentException("文档未解析出有效文本");
            }
            return text;
        }
    }

    private Map<String, Object> buildMetadata(RagDocument document, DocumentVersion version) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("documentId", document.getId());
        metadata.put("version", version.getVersion());
        metadata.put("status", DocumentStatus.ACTIVE.name());
        metadata.put("filename", document.getFilename());
        metadata.put("docType", document.getDocType());
        metadata.put("project", document.getProject());
        metadata.put("tags", document.getTags());
        metadata.put("description", document.getDescription());
        metadata.put("fileHash", version.getFileHash());
        metadata.put("uploadedAt", version.getCreatedAt().toString());
        return metadata;
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
