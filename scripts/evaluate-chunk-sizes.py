"""Compare fixed chunk sizes on the prepared MLDR subset without paid APIs.

The tokenizer mirrors BM25SparseEncoder: English/number/code runs plus Chinese
unigrams and bigrams. Scores are normalized log-TF cosine. This isolates chunk
size under lexical retrieval; it is not a semantic-splitter or reranker result.
"""
from __future__ import annotations

import json
import math
import re
import time
from pathlib import Path

import numpy as np
from scipy import sparse
from sklearn.feature_extraction.text import CountVectorizer
from sklearn.preprocessing import normalize


ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "target" / "mldr"
TOKEN_PATTERN = re.compile(r"[a-zA-Z0-9_#./:-]+|[\u3400-\u9fff]+")
CONFIGS = [(600, 40), (1200, 80), (1800, 120), (2400, 160)]
TOP_K = 6


def tokenize(text: str) -> list[str]:
    tokens: list[str] = []
    for match in TOKEN_PATTERN.finditer(text.lower()):
        token = match.group(0)
        if all("\u3400" <= char <= "\u9fff" for char in token):
            for index, char in enumerate(token):
                tokens.append(char)
                if index + 1 < len(token):
                    tokens.append(token[index:index + 2])
        else:
            tokens.append(token)
    return tokens


def read_jsonl(name: str) -> list[dict]:
    with (DATA / name).open("r", encoding="utf-8") as stream:
        return [json.loads(line) for line in stream if line.strip()]


def chunks(documents: list[dict], size: int, overlap: int):
    texts, doc_ids, lengths = [], [], []
    step = size - overlap
    for document in documents:
        text = document["text"]
        for start in range(0, len(text), step):
            value = text[start:start + size]
            if not value:
                break
            texts.append(value)
            doc_ids.append(document["id"])
            lengths.append(len(value))
            if start + size >= len(text):
                break
    return texts, np.asarray(doc_ids), lengths


def log_tf(matrix):
    matrix = matrix.astype(np.float32)
    matrix.data = np.log1p(matrix.data)
    return normalize(matrix, norm="l2", copy=False)


def evaluate(documents: list[dict], queries: list[dict], size: int, overlap: int) -> dict:
    started = time.perf_counter()
    texts, doc_ids, lengths = chunks(documents, size, overlap)
    vectorizer = CountVectorizer(tokenizer=tokenize, token_pattern=None, lowercase=False, dtype=np.float32)
    chunk_matrix = log_tf(vectorizer.fit_transform(texts))
    query_matrix = log_tf(vectorizer.transform([query["text"] for query in queries]))
    scores = (query_matrix @ chunk_matrix.T).tocsr()

    recalls, reciprocal_ranks, ndcgs = [], [], []
    for index, query in enumerate(queries):
        row = scores.getrow(index)
        if row.nnz:
            take = min(TOP_K, row.nnz)
            selected = np.argpartition(row.data, -take)[-take:]
            selected = selected[np.argsort(row.data[selected])[::-1]]
            ranked_chunks = row.indices[selected]
        else:
            ranked_chunks = np.array([], dtype=int)
        ranked_docs = []
        for chunk_index in ranked_chunks:
            doc_id = doc_ids[chunk_index]
            if doc_id not in ranked_docs:
                ranked_docs.append(doc_id)
        relevant = set(query["relevant"])
        hits = [rank + 1 for rank, doc_id in enumerate(ranked_docs) if doc_id in relevant]
        recalls.append(len({doc_id for doc_id in ranked_docs if doc_id in relevant}) / max(1, len(relevant)))
        reciprocal_ranks.append(1.0 / hits[0] if hits else 0.0)
        dcg = sum(1.0 / math.log2(rank + 1) for rank in hits)
        ideal_count = min(len(relevant), TOP_K)
        idcg = sum(1.0 / math.log2(rank + 1) for rank in range(1, ideal_count + 1))
        ndcgs.append(dcg / idcg if idcg else 0.0)

    return {
        "maxChars": size,
        "overlapChars": overlap,
        "chunks": len(texts),
        "chunkP50": int(np.percentile(lengths, 50)),
        "chunkP95": int(np.percentile(lengths, 95)),
        "recall@6": float(np.mean(recalls)),
        "mrr@6": float(np.mean(reciprocal_ranks)),
        "ndcg@6": float(np.mean(ndcgs)),
        "elapsedSeconds": round(time.perf_counter() - started, 2),
    }


def main():
    documents = read_jsonl("documents.jsonl")
    queries = read_jsonl("queries.jsonl")
    results = [evaluate(documents, queries, size, overlap) for size, overlap in CONFIGS]
    report = {
        "scope": "MLDR fixed-window lexical retrieval only; no semantic merging, embeddings, query rewrite or reranking",
        "documents": len(documents),
        "queries": len(queries),
        "topKChunks": TOP_K,
        "overlapRatio": "approximately 6.7%",
        "results": results,
    }
    (DATA / "chunk-size-comparison.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    lines = [
        "# MLDR chunk-size comparison", "",
        "Fixed-window lexical retrieval only; semantic merging, embeddings, rewrite and reranking are not included.", "",
        "| max chars | overlap | chunks | p50 | p95 | Recall@6 | MRR@6 | nDCG@6 | seconds |",
        "|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for item in results:
        lines.append(
            f"| {item['maxChars']} | {item['overlapChars']} | {item['chunks']} | {item['chunkP50']} | "
            f"{item['chunkP95']} | {item['recall@6']:.4f} | {item['mrr@6']:.4f} | "
            f"{item['ndcg@6']:.4f} | {item['elapsedSeconds']:.2f} |")
    (DATA / "chunk-size-comparison.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
