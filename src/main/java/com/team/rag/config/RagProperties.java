package com.team.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 知识库的集中配置。
 *
 * <p>所有模型、Pinecone、分块、检索和记忆参数都从 application.properties / .env 读取，
 * 避免把环境差异散落在业务代码里。</p>
 */
@Data
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private DashScope dashscope = new DashScope();
    private Pinecone pinecone = new Pinecone();
    private Document document = new Document();
    private SemanticSplitter semanticSplitter = new SemanticSplitter();
    private Hybrid hybrid = new Hybrid();
    private Memory memory = new Memory();

    @Data
    public static class DashScope {
        private String apiKey;
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        private String embeddingModel = "text-embedding-v3";
        private String chatModel = "glm-5";
        private Integer embeddingDimensions = 1024;
        private Double temperature = 0.1;
        private Boolean streamEnabled = true;
    }

    @Data
    public static class Pinecone {
        private String apiKey;
        private String index;
        private String region = "us-east-1";
        private String host;
        private String namespace = "team-rag-documents";
        private String memoryNamespace = "team-rag-memory";
    }

    @Data
    public static class Document {
        private Integer maxUploadSizeMb = 50;
        private String storageDir = "data/rag-documents";
    }

    @Data
    public static class SemanticSplitter {
        private Double similarityThreshold = 0.78;
        private Integer maxChars = 1800;
        private Integer overlapChars = 120;
    }

    @Data
    public static class Hybrid {
        private Integer topK = 6;
        private Double alpha = 0.65;
        private Double minScore = 0.0;
    }

    @Data
    public static class Memory {
        private Integer windowSize = 8;
        private Integer summaryThreshold = 12;
    }
}
