# TeamRagKnowledgeBase

团队 RAG 智能知识库，面向项目资料管理、研发规范查询和新人培训问答场景。

项目参考给定示例代码的结构实现，保留 `config / service / util / bean / controller` 分层，并补齐短期记忆、查询重写、长期记忆召回和 Pinecone dense + sparse hybrid 检索流程。

## 技术选型

- SpringBoot 3.2.6
- Java 17
- LangChain4j 1.0.0-beta3
- Apache Tika 文档解析
- 阿里百炼 `text-embedding-v3` 向量模型，1024 维
- 阿里百炼 `glm-5` 大模型
- Pinecone 向量数据库
- BM25 sparse vector + dense vector 混合检索
- Redis 短期记忆，未启动 Redis 时自动回退到进程内记忆
- WebFlux `Flux` 流式输出

> 说明：LangChain4j 作为 RAG 工程基线依赖保留。考虑到 `1.0.0-beta3` 的 DashScope / Pinecone 集成 API 在不同版本里变化较大，项目把模型调用和 Pinecone 调用封装成 Adapter，保证本项目更容易直接运行。

## 项目结构

```text
TeamRagKnowledgeBase/
├── pom.xml
├── .env.example
├── src/main/resources/
│   └── application.properties
└── src/main/java/com/team/rag/
    ├── TeamRagApplication.java
    ├── bean/
    │   ├── DocumentForm.java
    │   ├── RagQo.java
    │   ├── DocumentUploadResponse.java
    │   ├── DocumentSegment.java
    │   ├── RetrievedSegment.java
    │   ├── ChatMessage.java
    │   └── SparseVector.java
    ├── config/
    │   ├── DotenvApplicationListener.java
    │   ├── EmbeddingConfig.java
    │   ├── PineconeConfig.java
    │   ├── RAGConfig.java
    │   └── RagProperties.java
    ├── client/
    │   ├── DashScopeClient.java
    │   └── PineconeClient.java
    ├── util/
    │   ├── SemanticSplitter.java
    │   ├── BM25SparseEncoder.java
    │   ├── BM25Retriever.java
    │   └── PromptBuilder.java
    ├── service/
    │   ├── DocumentService.java
    │   ├── RagQAService.java
    │   ├── ShortTermMemoryService.java
    │   ├── QueryRewriteService.java
    │   └── LongTermMemoryService.java
    └── controller/
        ├── DocumentController.java
        ├── RagQAController.java
        └── GlobalExceptionHandler.java
```

## 核心流程

文档上传：

1. 上传文件和元数据：文档类型、所属项目、标签、描述。
2. Apache Tika 解析文档文本。
3. 添加 metadata。
4. `SemanticSplitter` 先基础切分，再用 embedding 余弦相似度合并语义块。
5. `text-embedding-v3` 向量化语义块。
6. `BM25SparseEncoder` 生成 sparse vector。
7. 写入 Pinecone，metadata 中保留原始文本。

问答：

1. 读取 Redis 短期记忆。
2. 基于短期记忆做 Query Rewriting。
3. 从长期记忆 namespace 召回相关事实。
4. 对改写查询同时生成 dense vector 和 sparse vector。
5. Pinecone hybrid query 融合语义检索与 BM25 关键词检索。
6. 融合短期记忆、长期记忆、RAG 片段和用户输入构建提示词。
7. 调用 glm-5 流式输出答案。
8. 答案完成后更新短期记忆。

兜底回答：

- 如果长期记忆和文档知识库都没有召回到可用片段，系统会按示例代码思路转通用大模型回答。
- 通用兜底答案会先标注“知识库未命中”，避免把通用模型推理误认为团队知识库事实。
- 可通过 `RAG_QA_GENERAL_FALLBACK_ENABLED=false` 关闭兜底，恢复严格知识库问答。

## 配置

项目会在启动时读取根目录 `.env`。当前已经按要求复用原 RepoPilot 项目的 `.env`；真实密钥不要提交到 Git。

DashScope 支持这些变量，按顺序兜底：

```properties
DASH_SCOPE_API_KEY=
REPOPILOT_GLM_API_KEY=
GLM_API_KEY=
DASHSCOPE_API_KEY=
```

Pinecone 至少需要：

```properties
PINECONE_API_KEY=
PINECONE_INDEX=
PINECONE_REGION=us-east-1
```

`PINECONE_HOST` 可选；不填时服务会通过 Pinecone 控制面 API 自动查询 index host。

问答兜底可选配置：

```properties
RAG_QA_GENERAL_FALLBACK_ENABLED=true
```

## 运行

```powershell
cd D:\JavaPrograms\TeamRagKnowledgeBase
mvn spring-boot:run
```

接口文档：

```text
http://localhost:8080/swagger-ui/index.html
```

## 测试接口

上传文档：

```powershell
curl -X POST "http://localhost:8080/api/document/upload" `
  -F "file=@D:\docs\Redis开发规范.pdf" `
  -F "docType=研发规范" `
  -F "project=baituan" `
  -F "tags=Redis,缓存,规范"
```

RAG 问答：

```powershell
curl -N -X POST "http://localhost:8080/api/rag/qa" `
  -H "Content-Type: application/json" `
  -d "{\"query\":\"Redis 缓存穿透如何解决？\",\"conversationId\":\"demo\",\"project\":\"baituan\"}"
```

## 生产优化

- 中文分词可替换为 IK / jieba，提高 BM25 sparse token 质量。
- 文档元数据和分块文本可落 MySQL，便于审计、删除和重建索引。
- Redis 短期记忆可扩展为滑动窗口 + 摘要 + 用户画像。
- 长期记忆可增加主动录入接口，写入 `team-rag-memory` namespace。
- 增加认证授权、限流熔断、ELK 日志和 Prometheus 指标。
