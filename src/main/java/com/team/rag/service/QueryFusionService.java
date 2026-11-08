package com.team.rag.service;

import com.team.rag.bean.RetrievedSegment;
import com.team.rag.config.RagProperties;
import com.team.rag.util.BM25Retriever;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 并行保留原始查询和改写查询的召回结果。
 * RRF 按名次融合，避免把两个查询产生的 score 直接相加造成尺度偏差。
 */
@Service
@RequiredArgsConstructor
public class QueryFusionService {
    private final BM25Retriever bm25Retriever;
    private final RagProperties properties;

    public List<RetrievedSegment> retrieve(String originalQuery, String rewrittenQuery, Integer requestedTopK,
                                           String namespace, Map<String, Object> filter) {
        int limit = candidateCount(requestedTopK);
        if (!Boolean.TRUE.equals(properties.getQueryFusion().getEnabled())) {
            return bm25Retriever.retrieve(rewrittenQuery, limit, namespace, filter);
        }
        List<RetrievedSegment> original = bm25Retriever.retrieve(originalQuery, limit, namespace, filter);
        if (!StringUtils.hasText(rewrittenQuery)
                || rewrittenQuery.trim().equals(originalQuery == null ? "" : originalQuery.trim())) {
            return original;
        }
        List<RetrievedSegment> rewritten = bm25Retriever.retrieve(rewrittenQuery, limit, namespace, filter);
        return fuse(original, rewritten, limit);
    }

    public int candidateCount(Integer requestedTopK) {
        int topK = requestedTopK == null || requestedTopK <= 0 ? properties.getHybrid().getTopK() : requestedTopK;
        int configured = properties.getQueryFusion().getCandidateCount();
        return Math.max(1, Math.min(100, Math.max(topK, configured)));
    }

    List<RetrievedSegment> fuse(List<RetrievedSegment> original, List<RetrievedSegment> rewritten, int limit) {
        Map<String, FusionEntry> entries = new LinkedHashMap<>();
        add(entries, original, weight(properties.getQueryFusion().getOriginalWeight()), "original");
        add(entries, rewritten, weight(properties.getQueryFusion().getRewrittenWeight()), "rewritten");
        return entries.values().stream()
                .sorted(Comparator.comparingDouble(FusionEntry::score).reversed())
                .limit(limit)
                .map(FusionEntry::segment)
                .toList();
    }

    private void add(Map<String, FusionEntry> entries, List<RetrievedSegment> hits, double weight, String source) {
        if (hits == null) return;
        int rrfK = Math.max(1, properties.getQueryFusion().getRrfK());
        for (int i = 0; i < hits.size(); i++) {
            RetrievedSegment segment = hits.get(i);
            if (segment == null || !StringUtils.hasText(segment.getId()) || !StringUtils.hasText(segment.getText())) continue;
            String key = segment.getId();
            FusionEntry current = entries.get(key);
            double contribution = weight / (rrfK + i + 1.0);
            if (current == null) {
                entries.put(key, new FusionEntry(segment, contribution, source));
            } else {
                current.score += contribution;
                current.source = current.source.equals(source) ? source : "both";
            }
        }
    }

    private double weight(Double value) {
        return value == null || !Double.isFinite(value) || value < 0 ? 0.0 : value;
    }

    private static class FusionEntry {
        private final RetrievedSegment segment;
        private double score;
        private String source;
        private FusionEntry(RetrievedSegment segment, double score, String source) {
            this.segment = segment;
            this.score = score;
            this.source = source;
        }
        private RetrievedSegment segment() { return segment; }
        private double score() { return score; }
    }
}
