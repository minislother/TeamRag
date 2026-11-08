package com.team.rag.util;

import com.team.rag.bean.RetrievedSegment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RAG 离线评测指标工具，面向“标准问题 -> 标准命中文档块”的小型评测集。
 */
@Component
public class RagEvaluationMetrics {

    /**
     * 根据人工标注的标准 chunkId 和实际检索结果，计算常见检索指标。
     */
    public RetrievalMetrics evaluateRetrieval(List<String> expectedSegmentIds,
                                              List<RetrievedSegment> retrievedSegments,
                                              int topK) {
        Set<String> expectedIds = normalizeExpectedIds(expectedSegmentIds);
        List<String> retrievedIds = normalizeRetrievedIds(retrievedSegments);
        int evaluationDepth = resolveEvaluationDepth(topK, retrievedIds.size());
        if (expectedIds.isEmpty() || evaluationDepth == 0) {
            return RetrievalMetrics.empty(evaluationDepth, expectedIds.size(), Math.min(retrievedIds.size(), evaluationDepth));
        }

        Set<String> matchedIds = new LinkedHashSet<>();
        int firstRelevantRank = 0;
        double dcg = 0.0D;
        int limit = Math.min(evaluationDepth, retrievedIds.size());
        for (int index = 0; index < limit; index++) {
            String retrievedId = retrievedIds.get(index);
            if (!expectedIds.contains(retrievedId) || matchedIds.contains(retrievedId)) {
                continue;
            }
            matchedIds.add(retrievedId);
            int rank = index + 1;
            if (firstRelevantRank == 0) {
                firstRelevantRank = rank;
            }
            dcg += discount(rank);
        }

        int relevantHits = matchedIds.size();
        double recallAtK = (double) relevantHits / expectedIds.size();
        double precisionAtK = (double) relevantHits / evaluationDepth;
        double hitAtK = relevantHits > 0 ? 1.0D : 0.0D;
        double reciprocalRank = firstRelevantRank == 0 ? 0.0D : 1.0D / firstRelevantRank;
        double ndcgAtK = normalizeDcg(dcg, expectedIds.size(), evaluationDepth);

        return new RetrievalMetrics(
                evaluationDepth,
                expectedIds.size(),
                limit,
                relevantHits,
                recallAtK,
                precisionAtK,
                hitAtK,
                reciprocalRank,
                ndcgAtK
        );
    }

    private Set<String> normalizeExpectedIds(List<String> expectedSegmentIds) {
        if (expectedSegmentIds == null) {
            return Set.of();
        }
        return expectedSegmentIds.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<String> normalizeRetrievedIds(List<RetrievedSegment> retrievedSegments) {
        if (retrievedSegments == null) {
            return List.of();
        }
        return retrievedSegments.stream()
                .filter(segment -> segment != null && StringUtils.hasText(segment.getId()))
                .map(segment -> segment.getId().trim())
                .collect(Collectors.toList());
    }

    private int resolveEvaluationDepth(int topK, int retrievedSize) {
        if (topK > 0) {
            return topK;
        }
        return retrievedSize;
    }

    private double normalizeDcg(double dcg, int expectedSize, int evaluationDepth) {
        int idealHits = Math.min(expectedSize, evaluationDepth);
        if (idealHits == 0) {
            return 0.0D;
        }
        double idealDcg = 0.0D;
        for (int rank = 1; rank <= idealHits; rank++) {
            idealDcg += discount(rank);
        }
        return idealDcg == 0.0D ? 0.0D : dcg / idealDcg;
    }

    private double discount(int rank) {
        return 1.0D / (Math.log(rank + 1) / Math.log(2));
    }

    /**
     * 一次检索评测的指标结果。
     */
    public static class RetrievalMetrics {

        private final int topK;
        private final int expectedCount;
        private final int retrievedCount;
        private final int relevantHits;
        private final double recallAtK;
        private final double precisionAtK;
        private final double hitAtK;
        private final double mrr;
        private final double ndcgAtK;

        public RetrievalMetrics(int topK,
                                int expectedCount,
                                int retrievedCount,
                                int relevantHits,
                                double recallAtK,
                                double precisionAtK,
                                double hitAtK,
                                double mrr,
                                double ndcgAtK) {
            this.topK = topK;
            this.expectedCount = expectedCount;
            this.retrievedCount = retrievedCount;
            this.relevantHits = relevantHits;
            this.recallAtK = recallAtK;
            this.precisionAtK = precisionAtK;
            this.hitAtK = hitAtK;
            this.mrr = mrr;
            this.ndcgAtK = ndcgAtK;
        }

        public static RetrievalMetrics empty(int topK, int expectedCount, int retrievedCount) {
            return new RetrievalMetrics(topK, expectedCount, retrievedCount, 0, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
        }

        public int getTopK() {
            return topK;
        }

        public int getExpectedCount() {
            return expectedCount;
        }

        public int getRetrievedCount() {
            return retrievedCount;
        }

        public int getRelevantHits() {
            return relevantHits;
        }

        public double getRecallAtK() {
            return recallAtK;
        }

        public double getPrecisionAtK() {
            return precisionAtK;
        }

        public double getHitAtK() {
            return hitAtK;
        }

        public double getMrr() {
            return mrr;
        }

        public double getNdcgAtK() {
            return ndcgAtK;
        }
    }
}
