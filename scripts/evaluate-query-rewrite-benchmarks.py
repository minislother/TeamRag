"""Offline query-rewrite benchmark.

This intentionally uses only annotations shipped with each dataset. It does not call
DashScope, Pinecone, an LLM, or pretend that a gold rewrite is a model prediction.
"""
import json, math, re, statistics
from pathlib import Path
from collections import Counter, defaultdict

ROOT = Path(__file__).resolve().parents[1] / "target" / "query-rewrite"
TOKEN = re.compile(r"[a-z0-9]+|[\u4e00-\u9fff]", re.I)

def toks(s): return TOKEN.findall((s or "").lower())
def overlap(a,b):
    aa, bb = Counter(toks(a)), Counter(toks(b))
    common = sum((aa & bb).values())
    p = common / max(sum(aa.values()), 1)
    r = common / max(sum(bb.values()), 1)
    return p, r, 2*p*r/max(p+r, 1e-12)
def avg(rows, key): return sum(r[key] for r in rows) / max(len(rows),1)
def ranks(values):
    return sorted(range(len(values)), key=lambda i: values[i], reverse=True)
def rank_hit(query, docs, target, k):
    q = Counter(toks(query)); scored=[]
    for i,d in enumerate(docs):
        terms=Counter(toks(d)); score=sum((1+math.log(1+q[t]))*math.log((len(docs)+1)/(1+sum(t in Counter(toks(x)) for x in docs))) for t in q if t in terms)
        scored.append((score,i))
    ordered=[i for _,i in sorted(scored, reverse=True)]
    if target not in ordered[:k]: return 0.0, 0.0
    rank=ordered.index(target)+1
    return 1.0, 1.0/rank

def rewrite_row(raw, gold):
    p,r,f=overlap(raw,gold)
    return {"precision":p,"recall":r,"f1":f,"exact":float(raw.strip().lower()==gold.strip().lower())}

def fused_text(raw, rewritten):
    """Approximate fusion for annotation-only comparison: preserve unique terms from both queries."""
    seen=set(); terms=[]
    for term in toks(raw)+toks(rewritten):
        if term not in seen:
            seen.add(term); terms.append(term)
    return " ".join(terms)

def main():
    report={"status":"COMPLETE","backend":"offline annotation metrics; no model/Pinecone calls","datasets":{}}
    q=json.loads((ROOT/"qrecc_test.json").read_text(encoding="utf8"))
    qrows=[rewrite_row(x.get("Question"),x.get("Truth_rewrite")) for x in q]
    report["datasets"]["QReCC"]={"records":len(q),"rewrite_metrics":{k:avg(qrows,k) for k in qrows[0]},"retrieval":"SKIPPED: test annotations expose passage URLs, not passage text/corpus"}

    c=json.loads((ROOT/"cast_manual.json").read_text(encoding="utf8")); crows=[]
    for topic in c:
        for turn in topic.get("turn",[]):
            crows.append({"raw":turn.get("raw_utterance"),"automatic":turn.get("automatic_rewritten_utterance"),"manual":turn.get("manual_rewritten_utterance")})
    auto=[rewrite_row(x["automatic"],x["manual"]) for x in crows]
    raw=[rewrite_row(x["raw"],x["manual"]) for x in crows]
    fused=[rewrite_row(fused_text(x["raw"],x["automatic"]),x["manual"]) for x in crows]
    report["datasets"]["TREC-CAsT-2020"]={"records":len(crows),"automatic_vs_manual":{k:avg(auto,k) for k in auto[0]},"raw_vs_manual":{k:avg(raw,k) for k in raw[0]},"retrieval":"SKIPPED: official corpus/qrels are separate and not downloaded"}
    report["datasets"]["TREC-CAsT-2020"]["raw_automatic_union_proxy"]={k:avg(fused,k) for k in fused[0]}

    t=[json.loads(line) for line in (ROOT/"topiocqa_valid.jsonl").open(encoding="utf8")]
    # TopiOCQA has no human rewrite. Compare raw question with all-history query only
    # as a retrieval-oriented proxy against the supplied gold passages.
    proxy=[]
    for x in t:
        context=" ".join(x.get("Context",[]))
        gold=x["Gold_passage"]["text"]
        docs=[gold]
        # Negative corpus is unavailable in this file, so this is explicitly not Recall@K.
        rawscore=overlap(x.get("Question"),gold)[2]
        historyscore=overlap(context+" "+x.get("Question",""),gold)[2]
        proxy.append({"raw_gold_overlap_f1":rawscore,"history_gold_overlap_f1":historyscore})
    report["datasets"]["TopiOCQA-valid"]={"records":len(t),"proxy_metrics":{k:avg(proxy,k) for k in proxy[0]},"retrieval":"SKIPPED: this file has one Gold_passage per row and no negative corpus; proxy only"}
    report["datasets"]["TopiOCQA-valid"]["comparison"]="history+question is the available context-expanded proxy; no separate model rewrite or gold rewrite exists"
    report["interpretation"]=[
        "The project comparison is raw retrieval vs project QueryRewriteService retrieval vs project RRF fusion; this offline script does not have the project's model rewrite output.",
        "QReCC and CAsT rewrite scores compare against human rewrites and do not measure retrieval quality.",
        "CAsT automatic rewrites are a public baseline, not this project's QueryRewriteService output.",
        "TopiOCQA intentionally has no human rewrite; its all-history overlap is only a diagnostic proxy.",
        "True retrieval Recall/MRR/nDCG requires each dataset's full corpus and qrels. Model-dependent rewritten retrieval and RRF fusion are skipped because DashScope returned Arrearage previously."
    ]
    (ROOT/"offline-query-rewrite-metrics.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf8")
    lines=["# Offline query-rewrite benchmark results","",report["backend"],""]
    for name,data in report["datasets"].items():
        lines += [f"## {name}","",f"records: {data['records']}",""]
        for k,v in data.items():
            if isinstance(v,dict): lines.append(f"- {k}: " + ", ".join(f"{m}={n:.4f}" for m,n in v.items()))
        lines += [f"- retrieval: {data.get('retrieval') or data.get('retrieval')}",""]
    lines += ["## Limits","",*['- '+x for x in report["interpretation"]]]
    (ROOT/"offline-query-rewrite-metrics.md").write_text("\n".join(lines)+"\n",encoding="utf8")
    print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__ == "__main__": main()
