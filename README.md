# TeamRagKnowledgeBase

baituan 社团资料 RAG 智能知识库，面向社团介绍、社团章程、招新材料、活动指引、预算报销、安全预案和活动复盘等资料的管理与问答场景。

项目采用 `config / service / util / bean / controller` 分层，包含短期记忆、查询重写、长期记忆召回和 Pinecone dense + sparse hybrid 检索流程。当前默认业务内容已切换为 baituan 社团资料知识库。

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
- Spring Security 可选登录认证、角色权限和项目级数据隔离
- JDBC 文档生命周期仓储，可用于 MySQL 生产持久化
- 本地文件 / S3 兼容对象存储，用于保存原始上传文档

> 说明：LangChain4j 作为 RAG 工程基线依赖保留。考虑到 `1.0.0-beta3` 的 DashScope / Pinecone 集成 API 在不同版本里变化较大，项目把模型调用和 Pinecone 调用封装成 Adapter，保证本项目更容易直接运行。

## 项目结构

```text
TeamRagKnowledgeBase/
├── pom.xml
├── .env.example
├── src/main/resources/
│   ├── application.properties
│   ├── static/
│   └── db/mysql/V1__document_lifecycle.sql
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
    ├── repository/
    │   ├── DocumentLifecycleRepository.java
    │   ├── InMemoryDocumentLifecycleRepository.java
    │   └── JdbcDocumentLifecycleRepository.java
    ├── util/
    │   ├── SemanticSplitter.java
    │   ├── BM25SparseEncoder.java
    │   ├── BM25Retriever.java
    │   ├── PromptBuilder.java
    │   ├── RagEvaluationMetrics.java
    │   └── RagAnswerJudgePromptBuilder.java
    ├── service/
    │   ├── DocumentService.java
    │   ├── DocumentIngestionWorker.java
    │   ├── RagQAService.java
    │   ├── ShortTermMemoryService.java
    │   ├── QueryRewriteService.java
    │   └── LongTermMemoryService.java
    ├── controller/
    │   ├── AuthController.java
    │   ├── DocumentController.java
    │   ├── RagQAController.java
    │   └── GlobalExceptionHandler.java
    └── security/
        ├── AccessControlService.java
        ├── SecurityTokenService.java
        ├── TokenAuthenticationFilter.java
        └── UserAccountService.java
```

## 核心流程

文档上传：

1. 上传社团资料文件和元数据：资料类型、所属项目/社团、标签、描述。
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
6. 开启安全时，根据用户可访问项目给长期记忆和文档检索追加 metadata filter。
7. 融合短期记忆、长期记忆、RAG 片段和用户输入构建提示词。
8. 调用 glm-5 流式输出答案。
9. 答案完成后更新短期记忆。

兜底回答：

- 如果长期记忆和文档知识库都没有召回到可用片段，系统会转为通用大模型回答。
- 通用兜底答案会先标注“社团资料知识库未命中”，避免把通用模型推理误认为 baituan 社团资料事实。

## 社团资料种子库

项目内置了一批可直接入库的 baituan 社团资料，位置：

```text
knowledge-base/baituan-club-materials/
```

资料覆盖：

- 社团介绍、社团基础信息模板、社团章程模板。
- 入驻认证、招新、活动立项、场地物资、预算报销流程。
- 活动安全预案、工作人员分工、宣传发布、会员管理、换届流程。
- 活动签到数据统计、星级评定、FAQ、活动方案、复盘模板、权限规范。

启动项目后可以批量上传种子资料：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/import-baituan-club-knowledge.ps1 `
  -BaseUrl "http://localhost:8080"
```

如果开启了登录认证，可附带 Token：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/import-baituan-club-knowledge.ps1 `
  -BaseUrl "http://localhost:8080" `
  -Token "your-jwt-token"
```

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

### 生产部署配置

生产建议至少开启 JDBC 生命周期仓储、对象存储和登录认证。

MySQL 表结构在 `src/main/resources/db/mysql/V1__document_lifecycle.sql`。当前项目没有引入 Flyway 自动迁移，生产部署前需要先在目标库执行该 SQL。

```properties
# 文档生命周期持久化
RAG_LIFECYCLE_REPOSITORY=jdbc
RAG_DB_URL=jdbc:mysql://127.0.0.1:3306/team_rag?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
RAG_DB_USERNAME=team_rag
RAG_DB_PASSWORD=change-me
RAG_DB_DRIVER_CLASS_NAME=com.mysql.cj.jdbc.Driver

# 原始文档对象存储，兼容 MinIO / S3
RAG_DOCUMENT_STORAGE_TYPE=s3
RAG_S3_ENDPOINT=http://127.0.0.1:9000
RAG_S3_REGION=us-east-1
RAG_S3_BUCKET=team-rag
RAG_S3_ACCESS_KEY=change-me
RAG_S3_SECRET_KEY=change-me
RAG_S3_PATH_STYLE_ACCESS=true
RAG_S3_PREFIX=rag-documents

# 入库 Worker 恢复扫描
RAG_INGESTION_RECOVERY_ENABLED=true
RAG_INGESTION_RECOVERY_FIXED_DELAY_MS=30000
RAG_INGESTION_RECOVERY_INITIAL_DELAY_MS=5000
RAG_INGESTION_RECOVERY_BATCH_SIZE=20

# 登录认证和项目权限
RAG_SECURITY_ENABLED=true
RAG_SECURITY_TOKEN_SECRET=replace-with-at-least-32-random-characters
RAG_SECURITY_TOKEN_TTL_MINUTES=720
RAG_SECURITY_USERS=admin:{bcrypt}xxxx:ADMIN:*;alice:{bcrypt}yyyy:USER:baituan|skating-club
```

`RAG_SECURITY_USERS` 的格式是：

```text
username:password:role:projectA|projectB;username2:password2:role:*
```

密码可以是明文，也可以使用 Spring Security 的 `{bcrypt}` 格式。生产环境建议只使用 `{bcrypt}`，并把该变量放在服务器密钥管理或 CI/CD Secret 中。

## 运行

```powershell
cd D:\JavaPrograms\TeamRagKnowledgeBase
mvn spring-boot:run
```

接口文档：

```text
http://localhost:8080/swagger-ui/index.html
```

网页端：

```text
http://localhost:8080/
```

如果 `RAG_SECURITY_ENABLED=true`，网页会先显示登录面板；如果未开启安全，网页会直接进入本地演示模式。

## 测试接口

上传文档：

```powershell
curl -X POST "http://localhost:8080/api/document/upload" `
  -F "file=@D:\docs\轮滑社社团章程.md" `
  -F "docType=社团章程" `
  -F "project=baituan" `
  -F "tags=社团,章程,组织架构"
```

RAG 问答：

```powershell
curl -N -X POST "http://localhost:8080/api/rag/qa" `
  -H "Content-Type: application/json" `
  -d "{\"query\":\"社团招新活动需要提前准备哪些材料？\",\"conversationId\":\"demo\",\"project\":\"baituan\"}"
```

## CI/CD

项目已增加 Gitee Go 流水线配置：`.workflow/team-rag-ci-cd.yml`。

触发条件：

- push 到 `main` 分支。
- 向 `main` 发起 Pull Request。

流水线执行内容：

1. `mvn -B -U clean test jacoco:report jacoco:check`
2. `mvn -B verify -Pintegration-tests`
3. 上传构建产物到 Gitee 默认制品库。
4. 发布 release 制品，制品名为 `team-rag-knowledge-base`。

覆盖率阈值在 `pom.xml` 中统一配置：

```properties
coverage.line.minimum=0.80
coverage.branch.minimum=0.75
coverage.method.minimum=1.00
```

流水线产物包括：

- `target/team-rag-knowledge-base-1.0.0.jar`
- `target/site/jacoco`
- `CODE_REVIEW_REPORT.md`
- `TEST_ACC_REPORT.md`

### Docker 部署

构建镜像：

```powershell
docker build -t team-rag-knowledge-base:latest .
```

使用 Compose 启动：

```powershell
docker compose -f deploy/docker-compose.prod.yml up -d --build
```

Compose 会读取项目根目录 `.env`。生产环境不要把真实 `.env` 提交到 Git，建议放在服务器目录或 Gitee Secret 中。

### systemd 主机部署

Linux 服务器可使用：

```bash
mvn -B -DskipTests package
scripts/deploy.sh
```

默认部署目录：

```text
/opt/team-rag-knowledge-base
```

默认 systemd 服务：

```text
team-rag.service
```

生产服务器需要提前准备：

- JDK 17
- MySQL
- Redis
- MinIO / S3
- 可访问的 Pinecone
- DashScope / GLM 相关 API Key
- `/opt/team-rag-knowledge-base/.env`

如果要做到真正自动部署到服务器，需要在 Gitee Go 中继续配置主机组或部署凭证，然后让部署步骤调用 `scripts/deploy.sh`。

## 生产优化

分块基准选型、实验指标与重排配置见 [分块评测集与召回后重排](docs/chunking-benchmarks-and-reranking.md)。文档召回默认使用 `gte-rerank-v2`：先召回 12 个候选，再取最终 topK（默认 6）；重排失败自动回退混合召回顺序。

- 中文分词可替换为 IK / jieba，提高 BM25 sparse token 质量。
- 对文档列表和入库任务增加分页、批量删除、重建索引、审计日志。
- Redis 短期记忆可扩展为滑动窗口 + 摘要 + 用户画像。
- 长期记忆可增加主动录入接口，写入 `team-rag-memory` namespace，并沿用项目权限 metadata。
- 增加接口限流、熔断、ELK 日志和 Prometheus 指标。
