# 单元测试覆盖率分析报告

## 1. 覆盖率统计

- 执行时间: 2026-06-06 03:14:45 +08:00
- 执行规则: `.codex/rules/TEST_ACC_RULES.md`
- 单元测试执行规则: `.codex/rules/TEST_RULES.md`
- 覆盖率命令: `mvn org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent test org.jacoco:jacoco-maven-plugin:0.8.12:report`
- 单元测试结果: Tests 32, Failures 0, Errors 0, Skipped 0
- 说明: JaCoCo 统计 Java 主代码覆盖率，前端静态资源通过截图和 JS 语法检查验证，不纳入 JaCoCo 指标。

| 指标 | 覆盖情况 | 覆盖率 | 目标 | 结论 |
| --- | ---: | ---: | ---: | --- |
| 行覆盖率 | 282/291 | 96.91% | >=80%，核心业务 >=90% | 通过 |
| 分支覆盖率 | 114/132 | 86.36% | >=75% | 通过 |
| 方法覆盖率 | 47/47 | 100.00% | 100% | 通过 |
| 类覆盖率 | 10/10 | 100.00% | 无硬性阈值 | 通过 |

**综合评价**: 优秀。

## 2. 已覆盖场景

### 正常场景

- ✅ 文档上传成功后解析、分块、向量化并写入 Pinecone。
- ✅ RAG 问答在知识库命中时构造示例风格 Prompt 并调用大模型。
- ✅ RAG 问答在知识库无命中时按示例逻辑调用通用大模型。
- ✅ 默认会话信息缺失时回退到 `memoryId=1`。
- ✅ 查询重写在存在短期记忆时调用大模型改写。
- ✅ 长期记忆召回调用独立 Pinecone namespace。
- ✅ BM25 + dense vector 混合检索参数传递正确。
- ✅ PromptBuilder 能过滤空知识片段并构造通用兜底 Prompt。
- ✅ 语义分块覆盖单片段、多片段、高相似度合并、超长无标点硬切块。

### 异常场景

- ✅ 文档文件为空时抛出异常。
- ✅ 上传文件超过限制时抛出异常。
- ✅ 文档解析为空文本时抛出异常。
- ✅ 查询重写大模型调用失败时回退原问题。
- ✅ 长期记忆召回失败时返回空集合。
- ✅ 短期记忆 Redis 读写失败时回退进程内 Map。

### 边界场景

- ✅ 空文本分块返回空列表。
- ✅ `topK` 为空或非法时使用默认配置。
- ✅ PromptBuilder 对 `null`、空集合、空文本片段做安全处理。
- ✅ BM25 sparse 编码覆盖中文和英文混合输入。
- ✅ `.env` 解析覆盖注释、export 前缀、引号和 BOM。

## 3. 未覆盖场景识别

### 🔴 高优先级（严重风险）

无。

### 🟡 中优先级（潜在风险）

无。

### 🔵 低优先级（优化项）

- [ ] **前端标签面板交互自动化断言**
  - 风险: 当前已通过 headless Chrome 截图确认默认态和标签面板展开态，但没有端到端测试自动断言点击、选择、新建标签后的 DOM 状态。
  - 建议: 后续若引入 Playwright / Selenium，可补充前端交互快照测试。

- [ ] **部分私有异常分支仍未完全逐行覆盖**
  - 风险: 当前总覆盖率和核心业务覆盖率均达标，剩余多为极端边界或日志兜底分支。
  - 建议: 如后续提高覆盖率门槛，可继续补充零向量、极端空白文本、Redis 写失败等更细粒度测试。

## 4. 代码分支分析

| 位置 | 分支条件 | 缺失测试 | 优先级 |
| --- | --- | --- | --- |
| `PromptBuilder.java:34` | rewrittenQuery 与 originalQuery 是否不同 | 部分短路分支未覆盖 | 低 |
| `PromptBuilder.java:63` | 过滤空 RetrievedSegment 文本 | 部分组合未覆盖 | 低 |
| `PromptBuilder.java:71` | messages 是否为空 | 部分短路分支未覆盖 | 低 |
| `BM25Retriever.java:34` | topK 默认值选择 | 一个短路分支未覆盖 | 低 |
| `BM25SparseEncoder.java:50` | tokenize 空输入 | 空输入行分支未完全覆盖 | 低 |
| `SemanticSplitter.java:51` | canMerge 判断 | 一个组合分支未覆盖 | 低 |
| `SemanticSplitter.java:73` | 空 unit continue | 空 unit 分支未覆盖 | 低 |
| `SemanticSplitter.java:100` | 空 paragraph continue | 空段落分支未覆盖 | 低 |
| `SemanticSplitter.java:105` | 空 sentence 过滤 | 空句子分支未覆盖 | 低 |
| `SemanticSplitter.java:132` | overlap 为 0 或 previous 短文本 | 一个组合分支未覆盖 | 低 |
| `SemanticSplitter.java:163` | 向量维度不同 | 维度不一致分支未覆盖 | 低 |
| `SemanticSplitter.java:174` | 零向量判断 | 零向量分支未覆盖 | 低 |
| `QueryRewriteService.java:26` | query / memory 为空判断 | 一个短路组合未覆盖 | 低 |
| `ShortTermMemoryService.java:44` | Redis raw 为空判断 | 一个组合分支未覆盖 | 低 |
| `RagQAService.java:58` | longMemory 与 ragSegments 命中组合 | 一个组合分支未覆盖 | 低 |
| `RagQAService.java:79` | memoryId 非空分支 | conversationId 优先时的组合未覆盖 | 低 |

## 5. 未覆盖的方法

无。方法覆盖率为 100.00%。

## 6. 补充测试用例建议

### 测试用例 1: SemanticSplitter 零向量相似度

```java
@Test
@DisplayName("语义分块 - 零向量相似度返回0")
void testSplit_ZeroVector_NotMerge() {
    // Given - 准备零向量 embedding
    // When - 执行 split
    // Then - 验证片段不会因相似度误判而合并
}
```

### 测试用例 2: 前端标签面板交互快照

```java
@Test
@DisplayName("前端标签 - 打开面板并新建标签")
void testTagPicker_CreateTag_ShowSelectedChip() {
    // Given - 打开页面
    // When - 点击标签控件并输入新标签
    // Then - 验证新标签出现在已选标签区域，隐藏 input 中同步提交值
}
```

## 7. 测试改进建议

### 代码可测试性改进

- 前端目前没有独立 E2E 测试工具链；如后续前端交互继续增加，可加入 Playwright。
- 对 `SemanticSplitter` 的私有相似度计算可通过更多公开 `split` 场景覆盖，不建议为了覆盖率直接暴露私有方法。

### 测试结构优化

- 当前测试类命名、中文 `@DisplayName`、Given-When-Then 结构整体符合 `TEST_RULES.md`。
- 后续可以按业务域把 RAG 主链路测试拆成嵌套类，便于阅读。

### Mock 策略优化

- 当前外部依赖如 DashScope、Pinecone、Redis、Tika 均已使用 Mock 或本地 fallback 场景覆盖。
- 后续可补充 Pinecone 返回空 metadata / malformed response 的客户端级测试。

## 8. 风险评估

- 生产风险等级: 低。
- 建议修复时间: 非阻塞，后续迭代补充低优先级边界测试即可。
- 结论: 当前覆盖率满足并超过项目覆盖率规则要求，无需因覆盖率阻塞本次前端优化。
