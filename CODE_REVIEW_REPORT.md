# Code Review Report

## 评审概览

- 变更意图: 面向大量、频繁变化文档的 RAG 项目，新增文档版本、异步入库、状态查询、旧版本隔离和前端状态展示。
- 影响范围: 文档上传链路、后台入库链路、Pinecone 查询过滤、文档状态接口、前端上传/状态展示、相关单元测试。
- 整体评分: 4.6/5
- 评审结论: 未发现 Critical 或 Warning 级别遗留问题。本轮评审中发现的两个可修复问题已完成修复并重新验证。

## Critical 问题

无。

## Warning 问题

无。

## Info 优化建议

### Info 1

- 问题类型: Info
- 位置: `src/main/java/com/team/rag/repository/DocumentLifecycleRepository.java`
- 问题描述: 当前生命周期仓储通过接口隔离，默认实现适合本地运行和功能验证。真正生产部署、多实例部署或强审计场景下，建议替换为 MySQL/PostgreSQL 等持久化实现。
- 影响: 当前实现已经能支持版本状态和 ACTIVE 检索过滤；后续生产化时，持久化仓储可以增强重启恢复、审计、分页查询和批量治理能力。
- 建议: 保持现有接口不变，新增数据库实现，并将文档状态、版本、入库任务、分块索引分别落表。
- 处理情况: 作为生产化后续增强项记录。
- 是否处理: 否，本次未引入数据库依赖。

## 已处理问题

### 已处理 1

- 问题类型: Warning
- 位置: `src/main/java/com/team/rag/service/DocumentService.java:66`
- 问题描述: 重复上传同一内容时，入库会跳过，但已落盘的临时版本文件需要清理，避免频繁上传后产生无用文件。
- 处理结果: 已新增 `DocumentStorageService.deleteQuietly(...)`，重复内容命中时主动删除临时文件。
- 是否处理: 是。

### 已处理 2

- 问题类型: Warning
- 位置: `src/main/java/com/team/rag/service/DocumentIngestionService.java:95`
- 问题描述: 入库任务 ID 不存在时，异常处理里直接标记失败可能触发二次异常。
- 处理结果: 已改为先 `findJob(jobId)`，只有任务存在时才标记失败。
- 是否处理: 是。

## 关键检查点

- 异步入库: `DocumentIngestionConfig` 提供独立线程池，上传接口不再长时间阻塞。
- 版本切换: `InMemoryDocumentLifecycleRepository.markIngestionSucceeded(...)` 在新版本成功后将旧 ACTIVE 版本标记为 SUPERSEDED。
- 检索隔离: `RagQAService.activeDocumentFilter(...)` 默认只检索 `status=ACTIVE` 的 Pinecone 片段，避免旧版本/失败版本进入 Prompt。
- 旧向量清理: `DocumentIngestionService` 成功入库后调用 `PineconeClient.deleteByIds(...)` 删除旧版本向量。
- 前端体验: 上传后可轮询入库任务，页面可查看文档状态并选择已有文档上传新版本。

## 验证结果

- 单元测试: `mvn test` 通过，55 tests, 0 failures, 0 errors, 0 skipped。
- 覆盖率: JaCoCo 通过生成，行覆盖率 97.41%，分支覆盖率 86.50%，方法覆盖率 100%。
- 结论: 当前变更满足本次规则要求，可进入提交/推送阶段。
