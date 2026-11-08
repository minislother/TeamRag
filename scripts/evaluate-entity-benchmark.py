"""Evaluate the public DBpedia-Entity v2 BM25 run against its qrels.

This is a reproducible entity/keyword retrieval reference.  It is deliberately
separate from the project three-way experiment: the latter needs the complete
collection and a working rewrite/embedding endpoint.
"""
from __future__ import annotations

import json
import math
from collections import defaultdict
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "target" / "entity"


def ndcg(ranked, grades, k):
    ranked = ranked[:k]
    dcg = sum((2 ** grades.get(doc, 0) - 1) / math.log2(i + 2) for i, doc in enumerate(ranked))
    ideal = sorted(grades.values(), reverse=True)[:k]
    idcg = sum((2 ** grade - 1) / math.log2(i + 2) for i, grade in enumerate(ideal))
    return dcg / idcg if idcg else 0.0


def main():
    qrels = defaultdict(dict)
    for line in (DATA / "qrels-v2.txt").read_text(encoding="utf-8").splitlines():
        qid, _, doc, grade = line.split()[:4]
        qrels[qid][doc] = int(grade)

    runs = defaultdict(list)
    for line in (DATA / "bm25.run").read_text(encoding="utf-8").splitlines():
        qid, _, doc, rank, score, _ = line.split()[:6]
        runs[qid].append((int(rank), float(score), doc))
    ranked = {qid: [doc for _, _, doc in sorted(rows)] for qid, rows in runs.items()}
    qids = sorted(set(qrels) & set(ranked))

    def aggregate(ids):
        values = {}
        for k in (10, 100):
            values[f"ndcg@{k}"] = sum(ndcg(ranked[q], qrels[q], k) for q in ids) / len(ids)
        values["queries"] = len(ids)
        return values

    categories = {}
    for prefix in ("SemSearch_ES", "INEX_LD", "QALD2", "ListSearch"):
        ids = [q for q in qids if q.startswith(prefix)]
        if ids:
            categories[prefix] = aggregate(ids)

    result = {
        "dataset": "DBpedia-Entity v2",
        "run": "public bm25.run",
        "qrels": "qrels-v2.txt",
        "metrics": aggregate(qids),
        "categories": categories,
        "scope": "public BM25 reference baseline; not a project raw/rewrite/fusion run",
    }
    out = DATA / "dbpedia-bm25-metrics.json"
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    md = DATA / "dbpedia-bm25-metrics.md"
    lines = ["# DBpedia-Entity v2 public BM25 baseline", "", f"Queries evaluated: {result['metrics']['queries']}", "", "| slice | queries | nDCG@10 | nDCG@100 |", "|---|---:|---:|---:|"]
    lines.append(f"| all intersecting qids | {result['metrics']['queries']} | {result['metrics']['ndcg@10']:.4f} | {result['metrics']['ndcg@100']:.4f} |")
    for name, values in categories.items():
        lines.append(f"| {name} | {values['queries']} | {values['ndcg@10']:.4f} | {values['ndcg@100']:.4f} |")
    lines += ["", "This is the dataset's published BM25 run, used as an entity/keyword retrieval reference. It is not evidence for the project's no-rewrite, rewrite, or fusion implementations."]
    md.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
