# TeamRagKnowledgeBase 单元测试覆盖率分析报告

## 1. 覆盖率统计

- 行覆盖率: 91.58% (目标 >= 80%，核心业务逻辑目标 >= 90%)
- 分支覆盖率: 77.03% (目标 >= 75%)
- 方法覆盖率: 100.00% (目标 100%，不含 JaCoCo 已排除的 bean/config/controller/client 等非核心代码)
- 指令覆盖率: 92.01%
- 复杂度覆盖率: 79.03%
- 异常覆盖率: JaCoCo 不单独输出异常覆盖率指标；核心异常路径已通过单元测试和集成测试覆盖。

综合评价: 优秀。当前行、分支、方法覆盖率均达到规则目标，生产化新增链路也补充了集成验证。

## 2. 已覆盖场景

### 正常场景

- 文档上传创建新文档、版本和入库任务。
- 已有文档上传新版本时沿用旧元数据。
- 文档解析、语义分块、embedding、Pinecone upsert 后激活版本。
- 新版本成功后旧版本变为 `SUPERSEDED`。
- RAG 问答在命中知识库时构造增强 Prompt 并调用 Chat Model。
- RAG 问答在未命中知识库时按项目策略返回无上下文提示。
- 短期记忆、长期记忆、查询重写的主要路径。
- Token 认证、用户角色、项目权限判断。
- S3 文档存储普通单元测试覆盖本地 mock 场景。

### 异常场景

- 文档解析为空时标记入库任务失败。
- 语义分块为空时标记入库任务失败。
- 入库任务不存在时失败处理。
- 文档版本不存在时失败处理。
- S3 存储删除、读取、路径解析相关异常路径。
- 认证 Token 无效、缺失、角色不匹配等安全路径。

### 边界场景

- 空检索结果。
- 空项目权限。
- 空或无效文档元数据。
- Redis 短期记忆滑动窗口与摘要边界。
- BM25 检索 topK 与无命中边界。

### 新增集成验证

- `FrontendSmokeIntegrationTest`: 启动轻量 HTTP Server，加载真实静态页面，完成文档列表加载、模型信息展示、问答 SSE、上传新版本入口检查。
- `JdbcDocumentLifecycleRepositoryIntegrationTest`: 在真实 MySQL 容器中验证迁移脚本、索引、事务回滚、新版本激活、失败状态标记。
- `S3DocumentStorageServiceIntegrationTest`: 在真实 MinIO 容器中验证保存、读取、删除文档对象。

## 3. 未覆盖场景识别

### 高优先级

无。当前整体覆盖率已达到规则阈值，核心业务主路径和主要异常路径已覆盖。

### 中优先级

- [ ] 真实 Pinecone/DashScope API 联调未纳入自动化测试。
  - 风险: 本地 mock 无法发现真实接口限流、网络抖动、模型返回格式变化等问题。
  - 建议: 后续单独增加受环境变量开关控制的外部 API smoke test，并只在 CI/预发布环境执行。

- [ ] 当前机器没有 Docker，MySQL/MinIO Testcontainers 用例本次被跳过。
  - 风险: 本机本次不能证明真实 MySQL/MinIO 链路可用。
  - 建议: 在安装 Docker 的 CI 或预发布机器执行 `mvn verify -Pintegration-tests`。

### 低优先级

- [ ] 个别类分支覆盖率低于 75%，但整体分支覆盖率达标。
  - 涉及类: `JdbcDocumentLifecycleRepository` 61.76%、`SecurityTokenService` 62.50%、`UserAccountService` 60.71%、`S3DocumentStorageService` 60.00%。
  - 建议: 后续围绕配置缺失、异常分支、空值分支继续补充测试。

## 4. 代码分支分析

| 位置 | 分支条件 | 缺失测试 | 优先级 |
|------|----------|----------|--------|
| `JdbcDocumentLifecycleRepository` | JDBC 查询、更新影响行数、JSON 解析异常等分支 | 更多真实数据库失败路径 | 低 |
| `SecurityTokenService` | token 解析、过期、签名异常组合 | 更多非法 token 组合 | 低 |
| `UserAccountService` | 用户配置缺失、角色和项目组合 | 更多权限边界组合 | 低 |
| `S3DocumentStorageService` | S3 endpoint/path/对象异常组合 | 更多 S3 异常响应 | 低 |

## 5. 未覆盖的方法

无。JaCoCo 方法覆盖率为 100.00%。

## 6. 补充测试用例建议

### 测试用例1: 真实 Pinecone/DashScope 冒烟验证

```java
@Test
@DisplayName("外部模型和向量库 - 真实环境可用")
void testExternalProviders_RealEnvironment_Success() {
    // Given - 读取显式启用的外部集成测试环境变量
    // When - 执行一次 embedding、Pinecone upsert/query、LLM 调用
    // Then - 验证返回向量维度、检索命中、LLM 响应非空
}
```

### 测试用例2: S3 网络失败路径

```java
@Test
@DisplayName("S3 存储 - endpoint 不可达抛出异常")
void testStore_UnreachableEndpoint_ThrowException() {
    // Given - 配置不可达 endpoint
    // When - 保存文档
    // Then - 验证异常和错误信息
}
```

## 7. 测试改进建议

### 代码可测试性改进

- 保持外部 API 测试与普通单元测试隔离，避免本地开发受到真实网络和密钥影响。
- 对真实 Pinecone/DashScope smoke test 使用显式环境变量开关，防止误消耗额度。

### 测试结构优化

- 当前已将真实依赖测试统一命名为 `*IntegrationTest`。
- 当前已通过 Surefire exclude 与 Failsafe include 分离单元测试和集成测试。

### Mock 策略优化

- 普通单元测试继续使用 mock 保证稳定性。
- 生产依赖链路使用 Testcontainers 或显式外部 smoke test 验证真实协议与配置。

## 8. 风险评估

- 生产风险等级: 低
- 建议修复时间: 本月继续补外部 API smoke test 与 CI/预发布 Docker 环境验证

## 9. 执行记录

- `mvn clean test jacoco:report`: BUILD SUCCESS；90 tests, 0 failures, 0 errors, 0 skipped。
- `mvn verify -Pintegration-tests`: BUILD SUCCESS；集成测试 6 个中 1 个前端 Playwright 冒烟测试通过，5 个 MySQL/MinIO Testcontainers 用例因当前机器无 Docker 自动跳过。
