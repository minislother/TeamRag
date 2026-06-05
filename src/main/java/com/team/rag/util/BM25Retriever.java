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

/**
 * BM25 + 向量混合检索工具。
 *
 * <p>参考代码里 BM25 是单独工具类。这里把 BM25 sparse 编码与 Pinecone hybrid query
 * 放在一起，让 Pinecone 按 dense/sparse 权重融合得分，避免从向量库全量拉文本。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BM25Retriever {

    private final BM25SparseEncoder sparseEncoder;
    private final DashScopeClient dashScopeClient;
    private final PineconeClient pineconeClient;
    private final RagProperties properties;

    /**
     * 执行混合检索。
     */
    public List<RetrievedSegment> retrieve(String query, Integer topK, String namespace) {
        int limit = topK == null || topK <= 0 ? properties.getHybrid().getTopK() : topK;
        // sparseVector 类似“关键词指纹”，用于 BM25/稀疏检索。
        SparseVector sparseVector = sparseEncoder.encode(query);
        // denseVector 是语义向量，用于找意思相近但字面不完全一样的片段。
        float[] denseVector = dashScopeClient.embedOne(query);
        // Pinecone hybrid query 会把 dense 和 sparse 的得分按 alpha 融合后排序。
        List<RetrievedSegment> segments = pineconeClient.query(
                namespace,
                denseVector,
                sparseVector,
                limit,
                properties.getHybrid().getAlpha(),
                properties.getHybrid().getMinScore()
        );
        log.info("混合检索完成，query={}, topK={}, hits={}", query, limit, segments.size());
        return segments;
    }
}
