package com.team.rag.service;

import com.team.rag.bean.RetrievedSegment;
import com.team.rag.util.BM25Retriever;
import com.team.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

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
        return recall(rewrittenQuery, Map.of());
    }

    /**
     * 鍙洖甯?metadata filter 鐨勯暱鏈熻蹇嗐€?     */
    public List<RetrievedSegment> recall(String rewrittenQuery, Map<String, Object> filter) {
        try {
            return bm25Retriever.retrieve(
                    rewrittenQuery,
                    Math.min(properties.getHybrid().getTopK(), 3),
                    properties.getPinecone().getMemoryNamespace(),
                    filter == null ? Map.of() : filter
            );
        } catch (Exception ex) {
            return List.of();
        }
    }
}
