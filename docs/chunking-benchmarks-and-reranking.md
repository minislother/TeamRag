# 分块评测集调研与召回后重排

调研日期：2026-09-28。已完成公开基准调研与重排代码接入；后续执行的 MLDR dev 数据准备、基础切分检查与边界修复见 [MLDR 评测记录](MLDR_EVALUATION.md)。尚未完成真实模型检索/重排质量评测，以下实验方案不代表已测得质量提升。

## 推荐测试集

| 基准及 GitHub | 规模 / 语言 | 对本项目的用途 | 局限 |
| --- | --- | --- | --- |
| [MLDR / FlagEmbedding](https://github.com/FlagOpen/FlagEmbedding/tree/master/research/C_MTEB/MLDR) | 13 种语言；中文语料 200,000 篇、dev 200 问、test 800 问 | **大型中文长文档首选**。用真实 SemanticSplitter 分块后做完整语料检索，对比分块参数和重排 | 标注主要在文档层，命中正确文档不代表命中了正确证据块 |
| [Chroma Chunking Evaluation](https://github.com/brandonstarxel/chunking_evaluation) | 多领域语料、查询及证据摘录；不是百万级压力集 | **直接衡量分块的首选补充**，计算证据 token recall、precision、IoU，识别切断答案及冗余上下文 | 主要面向英文；需要适配 Java 分块输出，不能直接拿其 Python chunker 代替本项目实现 |
| [C-MTEB / FlagEmbedding](https://github.com/FlagOpen/FlagEmbedding/tree/master/research/C_MTEB) | 中文多任务集合，含检索与重排任务 | 验证中文召回和重排；选择 retrieval/reranking 子集 | 许多样本已经是短段落，不能单独证明长文档分块合理 |
| [BEIR](https://github.com/beir-cellar/beir) | 多领域检索集合；其中 MS MARCO 语料约 884 万条，dev 查询 6,980 条 | 通用检索和重排对照；先用较小子集验证流程，再扩展大型语料 | 主要英文，各子集标注粒度、授权和规模不同；不是专用分块集 |
| [LongBench](https://github.com/THUDM/LongBench/tree/main/LongBench) | 中英双语，21 个任务、4,750 条测试数据 | 用中文长文 QA 检查“切块后检索再回答”是否遗漏跨段证据 | 原任务是长上下文理解，需要自行改造成 RAG 实验；摘要等任务不适合作为检索 qrels |

规模来源：[MLDR 官方数据卡](https://huggingface.co/datasets/Shitao/MLDR)、[BEIR 数据表](https://github.com/beir-cellar/beir#available-datasets)、[LongBench 原版说明](https://github.com/THUDM/LongBench/blob/main/LongBench/README.md)。GitHub 提供代码和下载入口，语料可能托管于 Hugging Face 或其他官方存储。固定仓库 commit / 数据 revision、划分与随机种子，并逐一检查数据集许可；仓库代码许可不自动等于所有语料许可。

## 如何判断本项目分块是否合理

1. 使用 MLDR `zh` 的 dev 调参，test 只用于最终评估。保留完整 20 万文档作为干扰语料；只索引正确答案文档会虚高召回率。初期可固定抽样查询降低费用，但需标注这是抽样结果。
2. 对比固定长度、按段落/句子切分和当前 `SemanticSplitter`。先固定 embedding、hybrid alpha、候选数与最终上下文预算，比较 `maxChars` 900/1800/2700、overlap 0/120/240、语义阈值 0.70/0.78/0.85。参数单位是字符，不能当 token。
3. 标注应绑定原文 `docId + start/end` 或证据摘录，而非一次切块产生的 chunkId。MLDR 文档级 qrels 需要将命中块映射回原始 docId，并按首次出现去重后计算文档 Recall@K、MRR、nDCG@K；不能把相关文档的每一块都视作相关证据。现有 `RagEvaluationMetrics` 使用二值 chunkId 标签，不能直接处理分级 qrels 或跨分块方案标签。
4. 使用 Chroma 证据摘录（并补充中文社团资料人工证据标注）计算检索证据覆盖率、precision、IoU、答案跨块断裂率。以原文位置区分重复词出现，不能只用文本 token 集合；重叠区间去重。还应统计块长分布、超限率、空块率、索引块数与实际 token 费用。
5. 先关闭重排比较分块，再固定最佳分块比较重排开/关。同一批已召回候选分别按 hybrid 分数和模型分数截取，才能隔离重排收益；同时报告候选 Recall@12、最终 Recall@6 / nDCG@6、端到端 QA、P50/P95 延迟和重排降级率。单纯切换生产开关会同时改变候选数，不能当作纯重排消融。
6. 在独立 Pinecone namespace / 测试项目中运行，保留 ACTIVE 和项目过滤。模型参数和数据 revision 随结果保存；不得把基准语料混入社团生产命名空间。

建议顺序：Chroma + 中文人工证据小集检查分块指标 → MLDR 中文 dev 调参 → MLDR 中文 test 全量语料验证 → BEIR / C-MTEB 检查泛化。公开基准不能代替活动通知、章程、表格和多版本社团资料的领域测试。

## 查询重写评测方法与大型测试集

查询重写应同时评估改写文本和下游检索，不能只用 BLEU/ROUGE 与人工 rewrite 做字面比较。建议固定会话历史和候选预算，做四组消融：原始查询、仅改写查询、原始+改写 RRF 融合、人工 gold rewrite 上限。每组报告 Recall@5/10/50、MRR@10、nDCG@10、Hit@K、重写延迟、检索延迟和模型调用成本；对多轮会话按 turn 分桶（首轮、代词指代、省略、主题切换）并配对 bootstrap 置信区间。还要记录“改写后丢失原始命中”的回退率，因为重写可能过度解释用户意图。

推荐公开数据集：

| 数据集 | 规模和标注 | 适合的实验 |
| --- | --- | --- |
| [QReCC](https://github.com/apple-aiml-research/ml-qrecc) | 约 14K 对话、81K 问答对；包含 Context、Question、Rewrite、Answer 和 URL，检索语料约 1,000 万网页/5,400 万段落 | 最适合同时评估 gold rewrite、LLM rewrite 和 passage retrieval；项目 README 提供 retrieval QA 评测脚本 |
| [TREC CAsT](https://treccast.ai/) | 对话式搜索 track，明确考查 query rewriting、document retrieval、reranking；配套会话、语料和 qrels | 最接近生产多轮检索链路；按年度数据和 qrels 固定版本，遵守官方获取条件 |
| [TopiOCQA](https://github.com/alexa/TopiOCQA) | 开放域多轮问答，包含话题切换和上下文依赖问题 | 检查重写模型在主题转移、隐含实体和省略问题上的鲁棒性；与 QReCC 交叉验证 |
| [OR-QuAC](https://github.com/prdwb/OR-QuAC) | QuAC 的开放检索版本，提供对话上下文、问题与检索相关性 | 评估“是否需要历史”的决策和原始/重写查询融合 |

## 项目名、编号、缩写和专有术语专项集

通用对话集不能充分覆盖“项目代号、工单编号、版本号、缩写、型号和罕见实体”。建议增加以下专项集：

| 数据集 | 覆盖重点 | 是否适合当前三方案 |
| --- | --- | --- |
| [DBpedia-Entity v2](https://github.com/iai-group/DBpedia-Entity) | 实体检索；包含 named entity 和型号式查询，例如 `08 toyota tundra`，约 460 万实体 | **最适合测试实体名、型号和精确关键词**；可直接比较 raw / rewritten / fusion 的实体 Recall@K、MRR、nDCG |
| [EntityQuestions](https://github.com/princeton-nlp/EntityQuestions) | 以实体为中心的问题，提供检索器评测代码和语料下载 | **适合实体名保持测试**；查询重写若删除实体名应计为 entity-loss error |
| [BEIR DBPedia-Entity](https://ir-datasets.com/beir.html) | DBpedia-Entity v2 的 BEIR 格式，提供 queries、corpus、qrels | **最容易接入统一评测脚本**；可用 BM25 做无模型基线，再接项目三条链路 |
| [NERetrieve](https://github.com/katzurik/neretrieve) | 命名实体提及检索和实体相关性 | 适合分析重写是否保留实体 mention；需要按其许可证和任务格式适配，不能直接当普通问答集 |
| BEIR 的 [DBPedia / TREC-COVID / SciFact](https://github.com/beir-cellar/beir/wiki/Datasets-available) | 专有实体、缩写和科学术语在文档和查询中较密集 | 适合做补充领域检索；其中 SciFact 更偏术语和证据，DBPedia 更偏实体 |

目前没有一个公开数据集能完整模拟本项目内部的“社团项目名 + 内部编号 + 自定义缩写”。因此最终还应从真实社团资料生成一个脱敏专项集，至少覆盖：原名、简称、大小写变体、连字符/下划线、数字编号、版本号、同名项目和只含代词的追问。该专项集必须保留原文证据和项目文档 ID，避免只评估 rewrite 文本相似度。

专项评测应增加以下指标：实体/编号保留率、关键 token Recall、实体丢失率、数字变更率、缩写扩展正确率、原始查询唯一命中保留率，以及三条链路的 Recall@5/10、MRR@10、nDCG@10。融合的重点不是 rewrite 文本 F1，而是防止改写删除原始关键词后造成召回丢失。

QReCC 官方说明指出，它同时覆盖查询重写、段落检索和阅读理解，并为每个问题提供独立 rewrite；其原始任务还允许同一答案分布在多个网页上。[QReCC 数据集说明](https://github.com/apple-aiml-research/ml-qrecc) CAsT 的研究流程则把重写、检索、重排作为独立阶段，适合作为本项目的阶段化消融依据。[CAsT 相关说明](https://treccast.ai/)

本项目新增的融合采用加权 RRF：原始查询权重默认 0.4，改写查询权重默认 0.6，分别召回后按 `weight / (rrfK + rank)` 合并相同 chunk，再交给既有重排模型。评测时必须比较三条项目链路：原始查询直接召回、仅改写查询召回、原始+改写召回融合；人工 rewrite 只作为标注或上限参考，不作为项目方案。这样保留原始关键词和项目术语，同时利用改写补全代词/省略；不直接相加两个查询的 BM25/向量分数。`RAG_QUERY_FUSION_ENABLED=false` 时保持仅改写查询的旧行为，便于线上消融。

## 重排模型与接入

选择 **gte-rerank-v2**：官方支持中文等多语言文本相关性排序，适合本项目已有百炼调用方式，无需新增本地 GPU 推理服务。此选择依据接入成本和任务匹配，并非已在本项目跑分证明优于其他模型。[官方 API](https://www.alibabacloud.com/help/en/model-studio/text-rerank-api)

候选替代：自托管时可评估 [BAAI/bge-reranker-v2-m3](https://github.com/FlagOpen/FlagEmbedding)，但需额外推理部署。`qwen3-rerank` 使用不同请求/响应协议，当前实现不能仅修改模型名切换到它；旧 `gte-rerank` 的退役提示也不能混同于本次使用的 `gte-rerank-v2`。

问答链路：查询重写 → 携带 ACTIVE/项目过滤的 hybrid 召回 → gte-rerank-v2 → 最终 topK → Prompt → glm-5。只重排文档候选，长期记忆维持原有独立召回逻辑。

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `RAG_RERANK_ENABLED` | `true` | 启用专用模型重排 |
| `RAG_RERANK_MODEL` | `gte-rerank-v2` | 使用百炼原生 rerank 协议的模型 |
| `RAG_RERANK_URL` | `https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank` | 独立完整 URL；按账号地域/工作空间覆盖，不从聊天兼容 URL 猜测 |
| `RAG_RERANK_CANDIDATE_COUNT` | `12` | 候选下限；不得少于最终 topK；应用最大 100 |
| `RAG_RERANK_TIMEOUT_SECONDS` | `10` | 整个重排请求等待上限 |

复用 `DASH_SCOPE_API_KEY`；请确保 API Key 的地域/工作空间支持所选模型。最终 topK 仍取请求参数或 `RAG_HYBRID_TOP_K`（默认 6），应用限制为 1–100；非正数请求使用默认值。关闭时不扩大召回；空/单条有效候选不调用模型。

上游超时、限流、权限错误、响应缺失、重复/越界下标或非法分数均回退原候选顺序并截取 topK。成功和降级分别记录日志，错误日志仅记录异常类型。原始 `score` 保留，模型分数另存 `rerankScore`；依据候选下标保留原文和元数据，不接受服务端替换正文。

官方 gte-rerank-v2 限制包括单项 4,000 tokens、单请求 30,000 tokens（查询会对每个候选重复计费/计入限制）。12 个候选是初始折中，不保证任意长查询和文档都不超限；超限时当前策略为降级，不静默截断证据。扩候选时需结合真实 tokenizer 与请求 token 预算，不能仅按字符数估算后断言安全。不同请求的相关性分数不应直接比较，因此当前不跨批次拼接分数。

当前验证使用 Mockito、模拟 HTTP 响应及本机 HTTP 服务，不调用真实百炼/Pinecone。部署前可通过实际问题确认日志出现“文档重排完成”；若出现降级日志，检查模型权限、URL/地域和 token 限额。单元测试通过不代表线上账号已完成连通性验证。
