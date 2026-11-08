"""Evaluate fixed chunk sizes with QASPER question-to-evidence annotations.

Metrics:
- MRR@10: rank of the first chunk that fully contains a highlighted evidence span.
- Precision@10: evidence-bearing chunks / returned top-10 chunks.
- Evidence Recall@10: gold evidence spans fully covered by at least one top-10 chunk.
- overlap redundancy: duplicated indexed characters / all indexed characters.
- intra-chunk similarity: macro mean of all sentence-pair lexical cosines per chunk.

The retrieval and cohesion representations are local log-TF vectors. They are
deterministic offline proxies and are not DashScope embedding measurements.
"""
from __future__ import annotations

import json
import math
import re
import time
from collections import Counter
from pathlib import Path

import numpy as np
from sklearn.feature_extraction.text import CountVectorizer
from sklearn.preprocessing import normalize


ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "target" / "qasper"
SOURCE = DATA / "qasper-dev-v0.3.json"
CONFIGS = [(600, 40), (1200, 80), (1800, 120), (2400, 160)]
TOKEN_PATTERN = re.compile(r"[a-zA-Z0-9_#./:-]+|[\u3400-\u9fff]+")
SENTENCE_BOUNDARY = re.compile(r"(?<=[.!?;。！？；])\s+")
TOP_K = 10


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


def paper_text(paper: dict) -> str:
    parts = [paper.get("title", ""), paper.get("abstract", "")]
    for section in paper.get("full_text", []):
        parts.append(section.get("section_name", ""))
        parts.extend(section.get("paragraphs", []))
    return "\n\n".join(part for part in parts if part)


def find_spans(text: str, snippets: list[str]) -> list[tuple[int, int]]:
    spans: set[tuple[int, int]] = set()
    for snippet in snippets:
        if not snippet:
            continue
        start = 0
        while True:
            start = text.find(snippet, start)
            if start < 0:
                break
            spans.add((start, start + len(snippet)))
            start += max(1, len(snippet))
    return sorted(spans)


def prepare(raw: dict):
    papers, questions = [], []
    answerable = 0
    for paper_id, paper in raw.items():
        text = paper_text(paper)
        papers.append((paper_id, text))
        for qa in paper.get("qas", []):
            highlighted, evidence = [], []
            has_answer = False
            for annotation in qa.get("answers", []):
                answer = annotation.get("answer", {})
                if answer.get("unanswerable"):
                    continue
                has_answer = True
                highlighted.extend(answer.get("highlighted_evidence", []))
                evidence.extend(answer.get("evidence", []))
            if not has_answer:
                continue
            answerable += 1
            spans = find_spans(text, highlighted) or find_spans(text, evidence)
            if spans:
                questions.append({"id": qa["question_id"], "text": qa["question"],
                                  "paper": paper_id, "spans": spans})
    return papers, questions, answerable


def make_chunks(papers, size, overlap):
    texts, owners, starts, ends = [], [], [], []
    original_chars = 0
    step = size - overlap
    for paper_id, text in papers:
        original_chars += len(text)
        for start in range(0, len(text), step):
            end = min(start + size, len(text))
            texts.append(text[start:end])
            owners.append(paper_id)
            starts.append(start)
            ends.append(end)
            if end == len(text):
                break
    return texts, np.asarray(owners), np.asarray(starts), np.asarray(ends), original_chars


def log_tf(matrix):
    matrix = matrix.astype(np.float32)
    matrix.data = np.log1p(matrix.data)
    return normalize(matrix, norm="l2", copy=False)


def counter_cosine(a: Counter, b: Counter) -> float:
    if not a or not b:
        return 0.0
    dot = sum(math.log1p(value) * math.log1p(b.get(token, 0)) for token, value in a.items())
    an = math.sqrt(sum(math.log1p(value) ** 2 for value in a.values()))
    bn = math.sqrt(sum(math.log1p(value) ** 2 for value in b.values()))
    return dot / (an * bn) if an and bn else 0.0


def intra_chunk_similarity(texts: list[str]) -> tuple[float, int, int]:
    chunk_means, pairs = [], 0
    for text in texts:
        sentences = [item.strip() for item in SENTENCE_BOUNDARY.split(text) if item.strip()]
        vectors = [Counter(tokenize(sentence)) for sentence in sentences]
        values = [counter_cosine(vectors[left], vectors[right])
                  for left in range(len(vectors)) for right in range(left + 1, len(vectors))]
        if values:
            chunk_means.append(float(np.mean(values)))
            pairs += len(values)
    return (float(np.mean(chunk_means)) if chunk_means else 0.0), pairs, len(chunk_means)


def evaluate(papers, questions, size, overlap):
    started = time.perf_counter()
    texts, owners, starts, ends, original_chars = make_chunks(papers, size, overlap)
    vectorizer = CountVectorizer(tokenizer=tokenize, token_pattern=None, lowercase=False, dtype=np.float32)
    chunks = log_tf(vectorizer.fit_transform(texts))
    queries = log_tf(vectorizer.transform([item["text"] for item in questions]))
    scores = (queries @ chunks.T).tocsr()
    reciprocal_ranks, precisions, evidence_recalls, evidence_chunk_counts = [], [], [], []
    for query_index, question in enumerate(questions):
        relevant = set()
        paper_chunks = np.flatnonzero(owners == question["paper"])
        for chunk_index in paper_chunks:
            if any(starts[chunk_index] <= begin and ends[chunk_index] >= end
                   for begin, end in question["spans"]):
                relevant.add(int(chunk_index))
        evidence_chunk_counts.append(len(relevant))
        row = scores.getrow(query_index)
        if not row.nnz or not relevant:
            reciprocal_ranks.append(0.0)
            precisions.append(0.0)
            evidence_recalls.append(0.0)
            continue
        take = min(TOP_K, row.nnz)
        selected = np.argpartition(row.data, -take)[-take:]
        selected = selected[np.argsort(row.data[selected])[::-1]]
        ranked = row.indices[selected]
        rank = next((rank for rank, chunk_index in enumerate(ranked, 1)
                     if int(chunk_index) in relevant), None)
        reciprocal_ranks.append(1.0 / rank if rank else 0.0)
        precisions.append(sum(int(chunk_index) in relevant for chunk_index in ranked) / len(ranked))
        covered_spans = sum(any(owners[chunk_index] == question["paper"]
                                and starts[chunk_index] <= begin and ends[chunk_index] >= end
                                for chunk_index in ranked)
                            for begin, end in question["spans"])
        evidence_recalls.append(covered_spans / len(question["spans"]))

    indexed_chars = sum(map(len, texts))
    cohesion, sentence_pairs, cohesion_chunks = intra_chunk_similarity(texts)
    return {
        "maxChars": size,
        "overlapChars": overlap,
        "chunks": len(texts),
        "mrr@10": float(np.mean(reciprocal_ranks)),
        "precision@10": float(np.mean(precisions)),
        "evidenceRecall@10": float(np.mean(evidence_recalls)),
        "evidenceHitRate@10": float(np.mean(np.asarray(reciprocal_ranks) > 0)),
        "questionsWithNoContainingEvidenceChunk": int(sum(count == 0 for count in evidence_chunk_counts)),
        "overlapRedundancy": (indexed_chars - original_chars) / indexed_chars,
        "intraChunkSentencePairCosine": cohesion,
        "sentencePairs": sentence_pairs,
        "chunksUsedForCohesion": cohesion_chunks,
        "elapsedSeconds": round(time.perf_counter() - started, 2),
    }


def main():
    raw = json.loads(SOURCE.read_text(encoding="utf-8"))
    papers, questions, answerable = prepare(raw)
    results = [evaluate(papers, questions, size, overlap) for size, overlap in CONFIGS]
    report = {
        "dataset": "QASPER v0.3 dev",
        "source": "https://qasper-dataset.s3.us-west-2.amazonaws.com/qasper-train-dev-v0.3.tgz",
        "papers": len(papers),
        "answerableQuestions": answerable,
        "questionsWithMatchedEvidence": len(questions),
        "scope": "fixed-window offline lexical retrieval; evidence is highlighted span when available",
        "metrics": {
            "mrr@10": "first chunk fully containing a gold evidence span",
            "precision@10": "evidence-bearing chunks / returned top-10 chunks, macro averaged by question",
            "evidenceRecall@10": "gold evidence spans covered by at least one returned top-10 chunk, macro averaged by question",
            "overlapRedundancy": "(indexed characters - source characters) / indexed characters",
            "intraChunkSentencePairCosine": "macro mean of all local log-TF sentence-pair cosines per chunk; not embedding semantic similarity",
        },
        "results": results,
    }
    (DATA / "chunk-size-comparison.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    lines = ["# QASPER chunk-size comparison", "",
             "Offline lexical retrieval with gold evidence spans; intra-chunk similarity is lexical, not embedding-based.", "",
             "| max chars | overlap | chunks | MRR@10 | Precision@10 | Evidence Recall@10 | hit@10 | redundancy | intra-chunk cosine | no containing chunk |",
             "|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for item in results:
        lines.append(f"| {item['maxChars']} | {item['overlapChars']} | {item['chunks']} | "
                     f"{item['mrr@10']:.4f} | {item['precision@10']:.4f} | "
                     f"{item['evidenceRecall@10']:.4f} | {item['evidenceHitRate@10']:.4f} | "
                     f"{item['overlapRedundancy']:.4f} | {item['intraChunkSentencePairCosine']:.4f} | "
                     f"{item['questionsWithNoContainingEvidenceChunk']} |")
    (DATA / "chunk-size-comparison.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
