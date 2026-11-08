package com.team.rag.util;

import com.team.rag.bean.RetrievedSegment;
import com.team.rag.bean.SparseVector;
import com.team.rag.client.DashScopeClient;
import com.team.rag.client.PineconeClient;
import com.team.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * BM25 + 向量混合检索工具。
 *
 * <p>这里把 BM25 sparse 编码与 Pinecone hybrid query 放在一起，
 * 让 Pinecone 按 dense/sparse 权重融合得分，避免从向量库全量拉文本。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BM25Retriever {

    private static final int FALLBACK_CANDIDATE_MULTIPLIER = 8;
    private static final int FALLBACK_MAX_CANDIDATES = 100;

    private final BM25SparseEncoder sparseEncoder;
    private final DashScopeClient dashScopeClient;
    private final PineconeClient pineconeClient;
    private final RagProperties properties;

    /**
     * 执行混合检索。
     */
    public List<RetrievedSegment> retrieve(String query, Integer topK, String namespace) {
        return retrieve(query, topK, namespace, Map.of());
    }

    /**
     * 执行带 metadata filter 的混合检索。
     */
    public List<RetrievedSegment> retrieve(String query, Integer topK, String namespace, Map<String, Object> filter) {
        int limit = topK == null || topK <= 0 ? properties.getHybrid().getTopK() : topK;
        // sparseVector 类似“关键词指纹”，用于 BM25/稀疏检索。
        SparseVector sparseVector = sparseEncoder.encode(query);
        // denseVector 是语义向量，用于找意思相近但字面不完全一样的片段。
        float[] denseVector = dashScopeClient.embedOne(query);
        List<RetrievedSegment> segments;
        try {
            // 优先让支持 sparse query 的 Pinecone 索引在服务端完成混合排序。
            segments = pineconeClient.query(
                    namespace,
                    denseVector,
                    sparseVector,
                    limit,
                    properties.getHybrid().getAlpha(),
                    properties.getHybrid().getMinScore(),
                    filter
            );
        } catch (PineconeClient.SparseQueryUnsupportedException ex) {
            // cosine 索引无法执行原生 sparse query 时，先扩大语义候选集，
            // 再按 dense + BM25 sparse 的同一 alpha 在客户端重排。
            segments = retrieveAndRerankLocally(namespace, denseVector, sparseVector, limit, filter);
            log.warn("Pinecone 索引不支持 sparse query，已使用客户端混合重排，candidateCount={}",
                    resolveFallbackCandidateCount(limit));
        }
        log.info("混合检索完成，topK={}, hits={}", limit, segments.size());
        return segments;
    }

    private List<RetrievedSegment> retrieveAndRerankLocally(String namespace,
                                                            float[] denseVector,
                                                            SparseVector querySparseVector,
                                                            int limit,
                                                            Map<String, Object> filter) {
        int candidateCount = resolveFallbackCandidateCount(limit);
        List<RetrievedSegment> candidates = pineconeClient.queryDense(
                namespace,
                denseVector,
                candidateCount,
                -Double.MAX_VALUE,
                filter
        );
        double alpha = properties.getHybrid().getAlpha();
        double minScore = properties.getHybrid().getMinScore();
        return candidates.stream()
                .filter(segment -> segment != null && segment.getText() != null)
                .map(segment -> withHybridScore(segment, querySparseVector, alpha))
                .filter(segment -> segment.getScore() >= minScore)
                .sorted((left, right) -> Double.compare(right.getScore(), left.getScore()))
                .limit(limit)
                .toList();
    }

    private RetrievedSegment withHybridScore(RetrievedSegment segment,
                                             SparseVector querySparseVector,
                                             double alpha) {
        // Pinecone cosine 得分范围理论上为 [-1, 1]，先映射到 [0, 1] 再与稀疏余弦相似度融合。
        double denseScore = Math.max(0.0D, Math.min(1.0D, (segment.getScore() + 1.0D) / 2.0D));
        SparseVector candidateSparseVector = sparseEncoder.encode(
                RetrievalTextBuilder.build(segment.getText(), segment.getMetadata()));
        double sparseScore = sparseCosine(querySparseVector, candidateSparseVector);
        double hybridScore = alpha * denseScore + (1.0D - alpha) * sparseScore;
        return RetrievedSegment.builder()
                .id(segment.getId())
                .text(segment.getText())
                .metadata(segment.getMetadata())
                .score(hybridScore)
                .build();
    }

    private double sparseCosine(SparseVector left, SparseVector right) {
        if (left == null || right == null || left.getIndices().isEmpty() || right.getIndices().isEmpty()) {
            return 0.0D;
        }
        Map<Integer, Double> rightValues = toSparseMap(right);
        double dot = 0.0D;
        double leftNorm = 0.0D;
        for (int i = 0; i < left.getIndices().size(); i++) {
            double value = left.getValues().get(i);
            dot += value * rightValues.getOrDefault(left.getIndices().get(i), 0.0D);
            leftNorm += value * value;
        }
        double rightNorm = right.getValues().stream().mapToDouble(value -> value * value).sum();
        return leftNorm == 0.0D || rightNorm == 0.0D
                ? 0.0D
                : dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private Map<Integer, Double> toSparseMap(SparseVector vector) {
        return java.util.stream.IntStream.range(0, vector.getIndices().size())
                .boxed()
                .collect(Collectors.toMap(
                        index -> vector.getIndices().get(index),
                        index -> vector.getValues().get(index),
                        Double::sum
                ));
    }

    private int resolveFallbackCandidateCount(int limit) {
        return Math.max(limit, Math.min(limit * FALLBACK_CANDIDATE_MULTIPLIER, FALLBACK_MAX_CANDIDATES));
    }
}
