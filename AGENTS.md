# AGENTS.md

## 项目概述
TeamRagKnowledgeBase 是一个 Spring Boot + DashScope/OpenAI 兼容模型 + Pinecone 的团队 RAG 知识库，提供文档上传入库和基于知识库的流式问答。

```text
资料上传 -> Tika 解析 -> 语义分块 -> embedding + sparse vector -> Pinecone
用户提问 -> 短期记忆 -> 查询重写 -> hybrid 检索 -> prompt -> glm-5 流式回答
```

## 开发命令
- 切换目录：`cd D:\JavaPrograms\TeamRagKnowledgeBase`
- 刷新 PowerShell PATH：`$env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')`
- 本地启动：`mvn spring-boot:run`
- 构建打包：`mvn package`
- 运行测试：`mvn test`
- 复制静态资源：`mvn process-resources`

## 关键目录
- `src/main/java/com/team/rag/controller/` - HTTP API 入口。
- `src/main/java/com/team/rag/service/` - 文档入库、问答、记忆和召回编排。
- `src/main/java/com/team/rag/client/` - DashScope 与 Pinecone REST 客户端。
- `src/main/java/com/team/rag/util/` - 语义分块、BM25、检索、提示词工具。
- `src/main/resources/static/` - 浏览器聊天前端。
- `.codex/rules/ai-readme/` - 项目规则文档。

## 边界约束
- 不要提交 `.env` 中的真实 API Key。
- 不要把模型和 Pinecone HTTP 调用散落到 Controller 或普通工具类；优先复用 `DashScopeClient` 和 `PineconeClient`。
- 修改模型默认值时，同步检查 `application.properties`、`RagProperties`、`.env.example` 和前端展示文案。
- `DocumentForm` 只有 `file` 是必填字段；`docType`、`project`、`tags`、`description` 是可选元数据。
- 前端静态资源修改后运行 `mvn process-resources`，避免启动中的应用仍加载旧资源。
- 真实密钥和业务约定写到人工维护文档或本地配置时，要避免暴露到公开仓库。

## 专项执行规则
- Code review：按 [@CODE_REVIEW_RULES](.codex/rules/CODE_REVIEW_RULES.md) 执行。
- 执行单元测试：按 [@TEST_RULES](.codex/rules/TEST_RULES.md) 执行。
- 单元测试覆盖率：按 [@TEST_ACC_RULES](.codex/rules/TEST_ACC_RULES.md) 执行。

## AI 上下文
详细规则见 `.codex/rules/ai-readme/README.md`：
- 架构：`.codex/rules/ai-readme/generated/技术架构.md`
- 流程：`.codex/rules/ai-readme/generated/核心流程.md`
- 结构：`.codex/rules/ai-readme/generated/项目结构.md`
- 开发：`.codex/rules/ai-readme/generated/开发指南.md`
- 业务（人工维护）：`.codex/rules/ai-readme/manual/业务知识.md`
- 踩坑（人工维护）：`.codex/rules/ai-readme/manual/历史经验.md`
