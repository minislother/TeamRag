package com.team.rag.service;

import com.team.rag.bean.DocumentForm;
import com.team.rag.bean.DocumentSegment;
import com.team.rag.bean.DocumentUploadResponse;
import com.team.rag.bean.SparseVector;
import com.team.rag.client.DashScopeClient;
import com.team.rag.client.PineconeClient;
import com.team.rag.config.RagProperties;
import com.team.rag.util.BM25SparseEncoder;
import com.team.rag.util.SemanticSplitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文档服务：上传、解析、语义分块、向量化、Pinecone 入库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final Tika tika;
    private final SemanticSplitter semanticSplitter;
    private final DashScopeClient dashScopeClient;
    private final PineconeClient pineconeClient;
    private final BM25SparseEncoder sparseEncoder;
    private final RagProperties properties;

    /**
     * 文档上传并入库向量库。
     */
    public DocumentUploadResponse uploadAndStore(DocumentForm form) {
        MultipartFile file = form.getFile();
        validateFile(file);
        String originalFilename = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        String documentId = UUID.randomUUID().toString();
        try (InputStream inputStream = file.getInputStream()) {
            String text = tika.parseToString(inputStream);
            if (!StringUtils.hasText(text)) {
                throw new IllegalArgumentException("文档未解析出有效文本");
            }
            Map<String, Object> metadata = buildMetadata(form, documentId, originalFilename, file.getSize());
            List<DocumentSegment> segments = semanticSplitter.split(documentId, text, metadata);
            List<String> segmentTexts = segments.stream().map(DocumentSegment::getText).toList();
            List<float[]> embeddings = dashScopeClient.embed(segmentTexts);
            List<PineconeClient.VectorRecord> records = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                DocumentSegment segment = segments.get(i);
                SparseVector sparseVector = sparseEncoder.encode(segment.getText());
                records.add(new PineconeClient.VectorRecord(segment.getId(), embeddings.get(i), sparseVector, segment.getMetadata()));
            }
            pineconeClient.upsert(properties.getPinecone().getNamespace(), records);
            log.info("文档 {} 入库成功，生成 {} 个语义块", originalFilename, segments.size());
            return DocumentUploadResponse.builder()
                    .documentId(documentId)
                    .filename(originalFilename)
                    .docType(form.getDocType())
                    .project(form.getProject())
                    .segmentCount(segments.size())
                    .message("文档上传并入库成功")
                    .build();
        } catch (Exception ex) {
            log.error("文档处理失败: {}", originalFilename, ex);
            throw new IllegalStateException("文档处理失败：" + ex.getMessage(), ex);
        }
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

    /**
     * 汇总文档元数据，供 Pinecone 过滤、展示和提示词引用使用。
     */
    private Map<String, Object> buildMetadata(DocumentForm form, String documentId, String filename, long size) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("documentId", documentId);
        metadata.put("filename", filename);
        metadata.put("docType", safe(form.getDocType(), "未分类"));
        metadata.put("project", safe(form.getProject(), "default"));
        metadata.put("tags", safe(form.getTags(), ""));
        metadata.put("description", safe(form.getDescription(), ""));
        metadata.put("size", size);
        metadata.put("uploadedAt", OffsetDateTime.now().toString());
        return metadata;
    }

    /**
     * 规范化可选表单字段，空值时回退到默认值。
     */
    private String safe(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }
}
