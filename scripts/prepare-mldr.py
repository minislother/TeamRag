"""Prepare a pinned MLDR Chinese dev pilot; never download into the production corpus."""
import gzip
import hashlib
import json
from pathlib import Path
import urllib.request

REVISION = "d67138e705d963e346253a80e59676ddb418810a"
ARCHIVE_SHA256 = "70d4b03a6862e5725311cb115de2d6c203e341d0608e18c2c5d6386920a7742f"
ROOT = Path(__file__).resolve().parents[1] / "target" / "mldr"


def prepare():
    ROOT.mkdir(parents=True, exist_ok=True)
    archive = ROOT / "dev.jsonl.gz"
    url = f"https://huggingface.co/datasets/Shitao/MLDR/resolve/{REVISION}/mldr-v1.0-zh/dev.jsonl.gz"
    if not archive.exists():
        temporary = archive.with_suffix(".part")
        urllib.request.urlretrieve(url, temporary)
        temporary.replace(archive)
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    if digest != ARCHIVE_SHA256:
        raise ValueError("MLDR archive checksum mismatch; do not use this download")
    with gzip.open(archive, "rt", encoding="utf-8") as source:
        rows = [json.loads(line) for line in source]
    documents, queries = {}, []
    for row in sorted(rows, key=lambda item: item["query_id"]):
        for passage in row["positive_passages"] + row["negative_passages"]:
            docid, text = passage["docid"], passage["text"]
            if docid in documents and documents[docid] != text:
                raise ValueError("Conflicting source document text")
            documents[docid] = text
        queries.append({"id": row["query_id"], "text": row["query"],
                        "relevant": sorted({p["docid"] for p in row["positive_passages"]})})
    if len(queries) != 200 or len({q["id"] for q in queries}) != 200:
        raise ValueError("Expected all 200 unique dev queries")
    for name, values in [("documents.jsonl", [{"id": k, "text": v} for k, v in sorted(documents.items())]),
                         ("queries.jsonl", queries)]:
        with (ROOT / name).open("w", encoding="utf-8", newline="\n") as output:
            for value in values:
                output.write(json.dumps(value, ensure_ascii=False) + "\n")
    manifest = {"dataset": "Shitao/MLDR", "revision": REVISION, "language": "zh", "split": "dev",
                "source": url, "archive_sha256": digest, "queries": len(queries), "documents": len(documents),
                "characters_python_codepoints": sum(map(len, documents.values())),
                "selection": "Union of ALL dev positives and seven provided negatives per query; sorted IDs; no random sampling",
                "scope": "Fixed dev pilot corpus, NOT official 200000-document retrieval benchmark",
                "files_sha256": {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest()
                                 for name in ["documents.jsonl", "queries.jsonl"]}}
    (ROOT / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: manifest[k] for k in ["revision", "queries", "documents", "characters_python_codepoints"]}))


if __name__ == "__main__":
    prepare()
