# 分块策略对照与调整

来源：用户保存的《理解RAG切块方式.html》，提取正文见 gpt-chunking-conversation.txt。以对话最后修订的建议为准。

## 对照结果
原实现采用字符基础片段、相邻 embedding 合并及固定字符 overlap；标题仅写 metadata，没有父子关系及精排后恢复，故与推荐方案不一致。

## 当前入库路径
DocumentIngestionService 调用 SemanticSplitter.splitAdaptive：按 Markdown / 中文章标题 / 数字标题切章节；短章节直接保留；长章节按自然句段的相邻向量相似度建立语义父块；父块超出预算时按 token 兜底并添加 overlap。每个子块保存绑定文档版本的 parentId、position、splitReason、tokenCount，以及基于标准化 UTF-16 文本的起止偏移。旧 split 方法保留供现有工具及基准调用。

Pinecone 只索引子块；现有生命周期仓储在 metadata_json 中保存关系及原文，无需新增表。父块通过同父子块的原文偏移重建，不在每条向量 metadata 中复制完整父块。

精排后，HARD_LIMIT 子块优先恢复预算允许的完整父块；否则尝试前后一个兄弟块，再退回命中块。限制在相同文档版本与父块内，检查文档和版本 ACTIVE 及当前用户项目权限；通过偏移去重。旧索引缺少关系时保留原命中。

## 配置与边界
- RAG_SEGMENT_MAX_TOKENS=500
- RAG_SEGMENT_OVERLAP_TOKENS=50
- RAG_CONTEXT_BUDGET_TOKENS=3000（仅本次 RAG 文档证据预算，不含系统提示、记忆、问题及输出）

使用 OpenAiTokenizer(text-embedding-3-small) 的 cl100k_base 计数作为统一预算代理，不宣称等于 DashScope/GLM 原生 token 数。语义边界向量使用每个自然单元最多 300 字符的前缀，正文仍完整保留；这是一项成本折中，需真实资料评估。

现阶段支持文本标题与自然段；不宣称完成代码 AST、PDF 表格结构解析、LLM contextual retrieval、证据覆盖二次检索或自动合并检索。对话把这些作为文档类型适配或后续评估方向，不在本次默认开启。

旧数据需要重新入库才能获得父子关系；相同文件哈希会触发原有重复入库跳过，需使用实际更新的文档版本或在维护流程中重建索引。本次未对现有 Pinecone 数据进行远程修改。质量提升仍需真实 Recall/nDCG/完整证据召回率和延迟实验验证。

## 类型分类与结构 metadata
入库对 PDF/Word/Excel/HTML 使用 Tika XHTML 结构事件保留 h1–h6、实际 page 标记及表格行列；Markdown/纯文本识别原有标题和管道表格。titlePath 保存标题层级，headingPath 保存路径。pageNumber 仅在解析器提供 page 标记时存在（Word 重排页码和无页文本不虚构）。updatedAt 来自文档更新时间；permissionTags 由服务端项目归属生成，普通 tags 不改变权限。

FAQ/问答/常见问题类型按 Q:/问:/问题: 或标题分离问答，预算上限为 200 代理 tokens，普通上限为 500；制度/章程/规范/办法/规定类型按章节、空行段落切分；管道表格及 XHTML 表格单独标记 TABLE，不与普通正文混合。超长内容仍保留 HARD_LIMIT 父子关系和预算扩展。PDF 扫描件、缺少逻辑标题或表格结构的文件受解析器能力限制，需要 OCR 或人工整理。
