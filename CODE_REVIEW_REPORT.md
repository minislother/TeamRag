# Code Review Report

## 评审概览

- 审查对象：`D:\JavaPrograms\TeamRagKnowledgeBase` 当前后端快照。
- 审查范围：`src/main/java`、`src/main/resources/application.properties`、`pom.xml`、`src/test/java`。
- 说明：当前目录不是 Git 仓库，无法基于 diff 评审；本次按当前代码快照进行架构和风险审查。
- 规则适配：项目的 `CODE_REVIEW_RULES.md` 仍写有旧路径 `club_springboot/`，本次按当前项目实际后端路径 `src/main/java` 执行。
- 测试结果：`mvn test` 通过，2 个测试成功。
- 整体评分：3.2 / 5。

## Critical 问题（必须修复）

### 1. 问答接口未按 `project` 元数据过滤，存在跨项目召回风险

- 问题类型：Critical
- 位置：
  - `src/main/java/com/team/rag/bean/RagQo.java:22`
  - `src/main/java/com/team/rag/service/DocumentService.java:101`
  - `src/main/java/com/team/rag/service/RagQAService.java:43`
  - `src/main/java/com/team/rag/util/BM25Retriever.java:33`
  - `src/main/java/com/team/rag/client/PineconeClient.java:77`
- 问题描述：上传时会把 `project` 写入 Pinecone metadata，问答入参也有 `project` 字段，但检索链路只传 namespace，没有把 `project` 转成 Pinecone filter。
- 影响：多个项目共用 `team-rag-documents` namespace 时，用户在项目 A 提问可能召回项目 B 的文档，导致答案污染或跨项目资料泄露。
- 建议：让 `BM25Retriever.retrieve` 和 `PineconeClient.query` 支持 metadata filter；当 `RagQo.project` 非空时传入 `{"project":{"$eq": project}}`。长期记忆 namespace 是否过滤 project 需要单独定义业务规则。
- 处理情况：待处理
- 是否处理：否
- 处理人：
- 时间：

### 2. 上传和问答接口没有鉴权、授权或限流保护

- 问题类型：Critical
- 位置：
  - `src/main/java/com/team/rag/controller/DocumentController.java:18`
  - `src/main/java/com/team/rag/controller/RagQAController.java:19`
  - `pom.xml:30`
- 问题描述：两个核心接口直接暴露，项目依赖中也没有 Spring Security 或等价认证授权组件。
- 影响：一旦服务部署到非本机环境，未授权用户可以上传任意资料、触发付费模型调用、写入 Pinecone，并读取知识库回答。
- 建议：至少增加 API token、登录态或网关鉴权；对 `/api/document/upload` 和 `/api/rag/qa` 增加用户/项目级授权；再配合上传和问答限流，避免模型费用和向量库写入被滥用。
- 处理情况：待处理
- 是否处理：否
- 处理人：
- 时间：

## Warning 问题（建议修复）

### 3. 上传链路只限制原始文件大小，没有限制解析文本量和 embedding 批次

- 问题类型：Warning
- 位置：
  - `src/main/resources/application.properties:4`
  - `src/main/java/com/team/rag/service/DocumentService.java:51`
  - `src/main/java/com/team/rag/util/SemanticSplitter.java:43`
  - `src/main/java/com/team/rag/service/DocumentService.java:58`
- 问题描述：当前限制的是 multipart 文件大小，Tika 解析出的文本会一次性进入内存；语义分块后也会一次性请求 embedding。
- 影响：复杂 PDF、Office 文件或压缩后膨胀的文本可能导致内存峰值、DashScope 请求过大、超时或费用异常。
- 建议：增加解析后最大字符数、最大分块数、embedding 分批大小；超过阈值时拒绝、截断或异步处理。
- 处理情况：待处理
- 是否处理：否
- 处理人：
- 时间：

### 4. 全局异常响应会把内部异常消息直接返回给客户端

- 问题类型：Warning
- 位置：
  - `src/main/java/com/team/rag/controller/GlobalExceptionHandler.java:45`
  - `src/main/java/com/team/rag/controller/GlobalExceptionHandler.java:47`
  - `src/main/java/com/team/rag/service/DocumentService.java:77`
- 问题描述：`handleError` 对所有未预期异常返回 `ex.getMessage()`，文档处理异常也会拼接外部调用或解析错误消息。
- 影响：客户端可能看到内部路径、第三方接口错误、配置缺失细节或其他不应暴露的信息。
- 建议：500 响应返回通用文案和 requestId；详细异常只写服务端日志。业务入参错误仍可返回明确 400 文案。
- 处理情况：待处理
- 是否处理：否
- 处理人：
- 时间：

### 5. 日志直接记录用户问题和改写后的查询，可能泄露敏感内容

- 问题类型：Warning
- 位置：
  - `src/main/java/com/team/rag/service/RagQAService.java:49`
  - `src/main/java/com/team/rag/util/BM25Retriever.java:45`
  - `src/main/resources/application.properties:45`
- 问题描述：RAG 问答和混合检索日志会记录原始 query 与 rewritten query，同时 `com.team.rag` 默认 DEBUG。
- 影响：用户问题里可能包含内部业务、账号、接口、密钥片段或未公开需求，进入日志后会扩大敏感信息暴露面。
- 建议：日志只保留 conversationId、长度、命中数量、耗时等非敏感指标；必要时对 query 做脱敏或摘要。生产环境默认不要开启 DEBUG。
- 处理情况：待处理
- 是否处理：否
- 处理人：
- 时间：

## Info 优化建议

### 6. 长期记忆召回和查询重写异常被静默吞掉，排障信号不足

- 问题类型：Info
- 位置：
  - `src/main/java/com/team/rag/service/QueryRewriteService.java:37`
  - `src/main/java/com/team/rag/service/LongTermMemoryService.java:30`
- 问题描述：两个服务捕获异常后直接回退，没有日志或指标。
- 影响：模型调用失败、Pinecone memory namespace 配置错误、网络超时等问题会表现为“效果变差”，但日志难以定位根因。
- 建议：增加 debug 或 warn 级别日志，记录失败阶段、conversationId 或 namespace，不记录完整用户问题和密钥。
- 处理情况：待处理
- 是否处理：否
- 处理人：
- 时间：

### 7. 当前测试只覆盖工具和 .env 解析，核心 RAG 链路缺少单元测试

- 问题类型：Info
- 位置：
  - `src/test/java/com/team/rag/config/DotenvApplicationListenerTest.java:11`
  - `src/test/java/com/team/rag/util/BM25SparseEncoderTest.java:5`
  - `src/main/java/com/team/rag/service/RagQAService.java:37`
  - `src/main/java/com/team/rag/service/DocumentService.java:45`
- 问题描述：现有 2 个测试均通过，但没有覆盖文档上传、项目过滤、异常响应、问答编排、记忆回退等核心路径。
- 影响：后续修改模型、Pinecone filter、异常处理或前端请求字段时，容易出现回归。
- 建议：为 `RagQAService`、`DocumentService`、`PineconeClient` 请求体构造、`GlobalExceptionHandler` 增加单元测试；外部模型和 Pinecone 用 mock 或 stub。
- 处理情况：待处理
- 是否处理：否
- 处理人：
- 时间：

## 总结

当前项目分层清晰，DashScope/Pinecone 调用集中在 client 层，短期记忆有 Redis 失败兜底，基础可运行性良好。最需要优先处理的是项目级检索隔离和接口访问控制；这两个问题直接影响知识库答案可信度和资料安全。其次建议补齐上传体量限制、异常响应脱敏和核心服务测试。
