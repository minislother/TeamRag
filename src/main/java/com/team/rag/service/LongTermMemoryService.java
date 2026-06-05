package com.team.rag.service;

import com.team.rag.bean.RetrievedSegment;
import com.team.rag.util.BM25Retriever;
import com.team.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 长期记忆召回服务。
 *
 * <p>长期记忆和普通文档使用不同 Pinecone namespace，方便后续把用户主动录入的事实、
 * 项目约定、人员偏好和历史决策分开管理。</p>
 */
@Service
@RequiredArgsConstructor
public class LongTermMemoryService {

    private final BM25Retriever bm25Retriever;
    private final RagProperties properties;

    /**
     * 召回长期记忆。
     */
    public List<RetrievedSegment> recall(String rewrittenQuery) {
        try {
            return bm25Retriever.retrieve(rewrittenQuery, Math.min(properties.getHybrid().getTopK(), 3), properties.getPinecone().getMemoryNamespace());
        } catch (Exception ex) {
            return List.of();
        }
    }
}
