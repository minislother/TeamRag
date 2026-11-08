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
    private QueryFusion queryFusion = new QueryFusion();
    private Rerank rerank = new Rerank();
    private Memory memory = new Memory();
    private Lifecycle lifecycle = new Lifecycle();
    private Datasource datasource = new Datasource();
    private DocumentStorage documentStorage = new DocumentStorage();
    private Ingestion ingestion = new Ingestion();
    private Security security = new Security();

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
        /** cl100k_base 计数作为可复现的预算代理，并非 GLM 原生 tokenizer。 */
        private Integer maxTokens = 500;
        private Integer overlapTokens = 50;
        private Integer contextBudgetTokens = 3000;
    }

    @Data
    public static class Hybrid {
        private Integer topK = 6;
        private Double alpha = 0.65;
        private Double minScore = 0.0;
    }

    /** 原始问题和改写问题的结果融合配置；RRF 避免直接比较两个查询的异构分数。 */
    @Data
    public static class QueryFusion {
        private Boolean enabled = true;
        private Double originalWeight = 0.4;
        private Double rewrittenWeight = 0.6;
        private Integer candidateCount = 12;
        private Integer rrfK = 60;
    }

    /** 专用重排模型配置，原生 DashScope 接口独立于聊天的 OpenAI-compatible URL。 */
    @Data
    public static class Rerank {
        private Boolean enabled = true;
        private String model = "gte-rerank-v2";
        private String url = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
        private Integer candidateCount = 12;
        private Integer timeoutSeconds = 10;
    }

    @Data
    public static class Memory {
        private Integer windowSize = 8;
        private Integer summaryThreshold = 12;
    }

    @Data
    public static class Lifecycle {
        private String repository = "memory";
    }

    @Data
    public static class Datasource {
        private String url;
        private String username;
        private String password;
        private String driverClassName = "com.mysql.cj.jdbc.Driver";
    }

    @Data
    public static class DocumentStorage {
        private String type = "local";
        private S3 s3 = new S3();
    }

    @Data
    public static class Ingestion {
        private Boolean recoveryEnabled = true;
        private Long recoveryFixedDelayMs = 30000L;
        private Long recoveryInitialDelayMs = 5000L;
        private Integer recoveryBatchSize = 20;
    }

    @Data
    public static class Security {
        /** 一个部署绑定一个租户，跨租户使用独立数据库和 namespace。 */
        private String tenantId = "default";
        private Boolean enabled = false;
        private String tokenSecret = "change-this-token-secret-before-production";
        private Long tokenTtlMinutes = 720L;
        private String users = "admin:admin123:ADMIN:*";
    }

    @Data
    public static class S3 {
        private String endpoint;
        private String region = "us-east-1";
        private String bucket;
        private String accessKey;
        private String secretKey;
        private Boolean pathStyleAccess = true;
        private String prefix = "rag-documents";
    }
}
