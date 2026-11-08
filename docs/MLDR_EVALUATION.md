# MLDR 中文 dev 评测记录

日期：2026-09-28。当前完成数据准备、实际基础切分检查和合并边界压力测试。**尚未完成真实语义分块、embedding 检索及重排质量对比。**

## 固定数据与评测范围

- 数据集：[Shitao/MLDR](https://huggingface.co/datasets/Shitao/MLDR)，revision `d67138e705d963e346253a80e59676ddb418810a`。
- 使用全部 200 个中文 dev 问题，每题 1 个正例、7 个官方附带干扰文档。所有文档取并集后为 **1,592 篇**，合计 **19,341,747 个 Unicode 码点**。
- 这是按 dev 关联文档构造的固定试验语料，不是从完整 corpus 随机抽样，也不是官方 20 万篇语料的全量检索评测。不能与 MLDR 官方排行榜直接比较。
- `dev.jsonl.gz` SHA-256：`70d4b03a6862e5725311cb115de2d6c203e341d0608e18c2c5d6386920a7742f`，已与源文件 LFS 哈希核对。
- 数据、manifest 和机器可读输出保存在被 Git 忽略的 `target/mldr/`。不写入业务 Pinecone namespace。

## 已完成的检查

直接调用生产 `SemanticSplitter.splitToBaseSegments`，使用项目默认配置（最终块上限 1,800，基础片段上限 600，overlap 120，语义阈值 0.78）。Java 长度按 UTF-16 code unit 计算，与上面的 Unicode 码点统计口径不同。

| 基础切分指标 | 结果 |
| --- | ---: |
| 文档数 | 1,592 |
| 基础片段数 | 36,029 |
| 长度 P50 / P95 / 最大值 | 578 / 600 / 600 |
| 空片段 | 0 |
| 超过基础长度上限的片段 | 0 |
| 非空白内容或顺序发生变化的文档 | 0 |
| UTF-16 代理对被边界切断 | 0 |

内容检查会移除空白后比较原文与全部基础片段拼接结果，不表示格式、表格布局或空白得到原样保留。这些结果仅证明基础切分的结构完整性，不能证明证据完整性或语义相关性。

## 找到并修复的问题

用人工向量分别触发“相邻片段全部相似”和“全部不相似”，对同一批 MLDR 原文执行完整生产分块器。**这里的向量仅用于分支压力测试，绝不是实际 embedding 或语义质量得分。**

修复前，“全部相似”场景生成 15,336 块，其中 40 块为 1,801 字符，突破配置的 1,800 上限。原因是合并判断只计算前后文本长度，漏算实际插入的 `\n`。已将换行计入预算，并补充 600 + 600 + 599 长度组合的回归用例，验证拆分结果和 overlap 保留情况。

原始证据见 `target/mldr/merge-boundary-before-fix.json`；修复后的同批检查见 `target/mldr/merge-boundary-check.json`。

| 人工向量边界场景 | 修复后块数 | 最大长度 | 超限块数 |
| --- | ---: | ---: | ---: |
| 相邻片段全部相似 | 15,352 | 1,800 | 0 |
| 相邻片段全部不相似 | 36,029 | 721 | 0 |

`mvn test`：119 项通过，0 失败、0 错误、0 跳过。评测 Java 入口已编译，并实际执行 `check` 模式；PowerShell 入口通过语法解析验证。真实模型 `run` 模式尚未完成，不能据离线检查推断其实际耗时或质量指标。

## 不同 Chunk 大小的离线检索对比

运行 `python scripts/evaluate-chunk-sizes.py`，在同一批 1,592 篇文档和 200 个查询上比较固定字符窗口。为了只比较大小，四组 overlap 都保持约 6.7%；取 Top 6 Chunk 后按文档 ID 去重。词法编码复用生产 `BM25SparseEncoder` 的中英文 token 思路，并用归一化 log-TF 余弦排序。

| 最大字符数 | overlap | Chunk 数 | Recall@6 | MRR@6 | nDCG@6 |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 600 | 40 | 35,220 | **0.7900** | **0.7337** | **0.7477** |
| 1,200 | 80 | 17,962 | 0.7750 | 0.6930 | 0.7136 |
| 1,800 | 120 | 12,193 | 0.7600 | 0.6964 | 0.7126 |
| 2,400 | 160 | 9,309 | 0.7550 | 0.6893 | 0.7060 |

在这组**固定窗口词法检索**中，600 字符取得最高三项指标，但 Chunk 数约为 1,800 字符方案的 2.89 倍，意味着更多索引、Embedding 和存储成本。结果支持“小块更利于精确关键词定位”，不能直接证明生产语义分块应把最终上限改成 600：生产配置的 1,800 是合并上限，实际先生成约 600 字符基础片段，只有相邻语义相似时才合并。真实 Embedding、语义合并和重排比较仍需模型接口恢复后执行。

完整结果写入 `target/mldr/chunk-size-comparison.json` 和 `.md`。

## 暂未执行的真实模型实验

真实接口预检中，embedding 和 `gte-rerank-v2` 均返回 HTTP 400 / `Arrearage`。按用户要求跳过该账号问题，本轮不再调用模型，也不生成 Recall/MRR/nDCG 的虚拟分数。

真实模型脚本已经提供，但线上完整路径尚未验证：

1. `fixed` 基线按字符固定长度切分，`semantic` 直接调用生产 Java 语义分块器；两者共用长度和 overlap 配置。
2. embedding 使用真实 `DashScopeClient`，测试适配层按每批最多 10 条发送并缓存结果。缓存键包含接口地址、模型、维度和原文哈希；缓存不含 API Key。
3. 为隔离业务向量库，使用内存精确余弦搜索替代 Pinecone 网络 I/O，强制进入生产 `BM25Retriever` 的客户端融合分支（dense 96 候选 → hybrid 12）。此实验不能声称覆盖了 Pinecone 服务端原生 hybrid 或 ANN 行为。
4. 同一组 12 个候选对比直接取前 6 与真实 `RerankService` 重排后取 6，记录降级次数和每题明细。
5. 先取实际块结果，再映射到原文 docId 并去重，计算文档级 Recall/MRR/nDCG。预算是 6 个块，可能不足 6 个唯一文档；不衡量摘录级证据覆盖率，也不调用查询重写、会话记忆或答案生成。

## 复现命令

在项目根目录执行，只做无需模型的检查：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-mldr.ps1 -Mode check
```

当本地 `.env` 中的模型账号恢复可用后，显式执行以下命令才会调用真实模型并产生费用：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-mldr.ps1 -Mode run
```

每次执行会校验固定数据版本并重新生成输入清单。`run` 会先检查模型连通性，通过后运行两种分块策略；缓存可减少重跑时的 embedding 调用。成功完成的结果写入 `target/mldr/metrics.json`，每题详情写入 `fixed-queries.json` / `semantic-queries.json`。读取指标前应先检查 `run-status.json` 是否为 `COMPLETE`，失败或未运行时不能把遗留指标当作本轮完整结果。

## 查询重写数据集离线结果

已下载并运行 `scripts/evaluate-query-rewrite-benchmarks.py`。该脚本只计算公开标注中的词法重叠指标，不调用模型或向量库：

| 数据集 | 样本数 | 结果 |
| --- | ---: | --- |
| QReCC test | 16,451 | `Question` 对 `Truth_rewrite`：Precision 0.8692、Recall 0.6125、F1 0.6990、Exact 0.1007；检索跳过，因为该文件提供 passage URL 而不是正文语料 |
| TREC CAsT 2020 | 216 | 官方 automatic rewrite 对 manual rewrite：F1 0.7754；raw query 对 manual rewrite：F1 0.7337；完整检索跳过，因为官方 corpus/qrels 未下载 |
| TopiOCQA valid | 2,514 | 无人工 rewrite；Question 对 Gold passage 的词法 F1 0.0511，Context+Question 对 Gold passage 的诊断 F1 0.2206；这不是 Recall@K |

这些数值用于检查数据和改写标注链路，不能当作本项目 LLM 查询重写的准确率。QReCC、CAsT 的真实检索 Recall/MRR/nDCG，以及 TopiOCQA 的完整 corpus 检索，需要模型和完整语料/qrels；当前按账号额度问题跳过。

## 三种方案的当前对比结论

本项目真正需要比较的三条链路是：

1. **不重写**：原始用户问题直接召回。
2. **查询重写**：`QueryRewriteService` 生成项目查询后，仅用改写查询召回。
3. **召回融合**：原始查询和改写查询分别召回，再用项目的加权 RRF 合并，之后进入相同的重排和 topK 截取。

当前 CAsT 的 `raw_vs_manual`、`automatic_vs_manual` 和 token-union 数值只是公开人工标注/官方 automatic baseline 的参考，**不是这三个项目方案的检索结果**。由于 DashScope 账号不可用，本轮没有项目模型 rewrite，也没有真实的三路 Recall@K、MRR@10 或 nDCG@10，因此不能据此判断三条项目链路的优劣。

等模型可用后，评测脚本必须对同一批会话分别记录 `raw_results`、`rewritten_results`、`fusion_results`，并用同一候选预算、同一 reranker、同一 qrels 计算三组指标。人工 rewrite 只用于计算 rewrite 质量或作为 gold upper-bound，不作为项目的第三种方案。
