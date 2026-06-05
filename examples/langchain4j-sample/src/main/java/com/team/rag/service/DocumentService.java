package com.team.rag.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.parser.apache.poi.ApachePoiDocumentParser;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.pinecone.PineconeEmbeddingStore;
import com.team.rag.util.SemanticSplitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

/**
 * 文档服务：上传、解析、语义分块、向量化、入库
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final SemanticSplitter semanticSplitter;
    private final EmbeddingModel embeddingModel;
    private final PineconeEmbeddingStore pineconeEmbeddingStore;

    /**
     * 文档上传并入库向量库
     * @param file 文档文件
     * @param docType 文档类型
     * @return 入库结果
     */
    public String uploadAndStore(MultipartFile file, String docType) {
        try {
            // 1. 校验文件
            if (file.isEmpty()) {
                return "文档文件不能为空";
            }
            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null) {
                return "文档文件名无效";
            }
            log.info("开始处理文档：{}，类型：{}", originalFilename, docType);

            // 2. 临时保存文件（用于解析）
            File tempFile = File.createTempFile(UUID.randomUUID().toString(), originalFilename.substring(originalFilename.lastIndexOf(".")));
            file.transferTo(tempFile);

            // 3. 根据文件类型选择解析器
            Document document;
            if (originalFilename.endsWith(".pdf")) {
                document = FileSystemDocumentLoader.loadDocument(tempFile.getAbsolutePath(), new ApachePdfBoxDocumentParser());
            } else if (originalFilename.endsWith(".doc") || originalFilename.endsWith(".docx")) {
                document = FileSystemDocumentLoader.loadDocument(tempFile.getAbsolutePath(), new ApachePoiDocumentParser());
            } else {
                // MD/TXT/HTML使用默认解析器
                document = FileSystemDocumentLoader.loadDocument(tempFile.getAbsolutePath());
            }
            // 添加文档元数据
            document.metadata().add("name", originalFilename);
            document.metadata().add("type", docType);
            document.metadata().add("size", file.getSize() + "B");

            // 4. 语义分块（核心）
            List<TextSegment> semanticSegments = semanticSplitter.split(document);

            // 5. 向量化并入库Pinecone
            EmbeddingStoreIngestor.builder()
                    .embeddingModel(embeddingModel)
                    .embeddingStore(pineconeEmbeddingStore)
                    .build()
                    .ingest(semanticSegments);

            // 6. 删除临时文件
            Files.deleteIfExists(tempFile.toPath());

            log.info("文档[{}]处理完成，成功入库{}个语义块", originalFilename, semanticSegments.size());
            return String.format("文档[%s]上传并入库成功，生成%d个语义块", originalFilename, semanticSegments.size());
        } catch (Exception e) {
            log.error("文档处理失败", e);
            return "文档处理失败：" + e.getMessage();
        }
    }
}
