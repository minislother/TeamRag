# 实体、项目名与专有术语检索评测

本轮实际运行了 [DBpedia-Entity v2](https://github.com/iai-group/DBpedia-Entity) 的公开 BM25 run。该数据集包含 467 个查询、约 460 万实体文档，并提供 0/1/2 分级 qrels，适合检查项目名、实体名、缩写和术语类查询的精确匹配能力。

运行命令：

```powershell
python scripts/evaluate-entity-benchmark.py
```

结果保存在 `target/entity/dbpedia-bm25-metrics.md` 和 JSON 文件中。公开 BM25 run 在 467 个交集查询上得到：

| 指标 | 数值 |
|---|---:|
| nDCG@10 | 0.2388 |
| nDCG@100 | 0.3443 |

该结果是数据集自带 BM25 参考基线，不是本项目三种方案的结果。当前不能诚实地计算“原查询召回、查询重写召回、两者融合”在 DBpedia-Entity 上的三方对比：完整 460 万实体语料尚未接入项目索引，而且 DashScope 账号目前返回 `Arrearage`，查询重写、embedding 与 rerank 链路均不可用。按用户要求，这些后续链路已跳过，没有用人工改写或代理数字冒充项目结果。

待模型和语料可用后，应对同一批 qids 分别运行原查询、`QueryRewriteService` 输出、加权 RRF 融合，并统一使用 `qrels-v2.txt` 计算 nDCG@10/100、Recall@100 和 MRR；专项切片可按 `SemSearch_ES`、`INEX_LD`、`QALD2` 分开报告。
