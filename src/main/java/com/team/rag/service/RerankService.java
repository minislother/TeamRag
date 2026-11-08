package com.team.rag.service;

import com.team.rag.bean.RetrievedSegment;
import com.team.rag.client.DashScopeClient;
import com.team.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.team.rag.util.RetrievalTextBuilder;

import java.util.List;

/** 在已完成权限过滤的文档召回之后重排；服务不可用时保留混合召回结果。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RerankService {
    private final DashScopeClient dashScopeClient;
    private final RagProperties properties;

    public int finalTopK(Integer requested) {
        int value = requested == null || requested <= 0 ? properties.getHybrid().getTopK() : requested;
        return Math.max(1, Math.min(100, value));
    }

    /** 候选池与最终上下文数量分开，防止只重排已经截断的 topK。 */
    public int candidateCount(Integer requested) {
        int topK = finalTopK(requested);
        return Boolean.TRUE.equals(properties.getRerank().getEnabled())
                ? Math.max(topK, Math.min(100, properties.getRerank().getCandidateCount())) : topK;
    }

    public List<RetrievedSegment> rerank(String query, List<RetrievedSegment> candidates, Integer requested) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<RetrievedSegment> usable = candidates.stream()
                .filter(segment -> segment != null && StringUtils.hasText(segment.getText()))
                .limit(candidateCount(requested)).toList();
        int topK = finalTopK(requested);
        List<RetrievedSegment> fallback = usable.stream().limit(topK).toList();
        if (!Boolean.TRUE.equals(properties.getRerank().getEnabled()) || usable.size() < 2) {
            return fallback;
        }
        try {
            List<DashScopeClient.RerankResult> results = dashScopeClient.rerank(
                    query,
                    usable.stream().map(segment -> RetrievalTextBuilder.build(
                            segment.getText(), segment.getMetadata())).toList(),
                    Math.min(topK, usable.size()));
            List<RetrievedSegment> ranked = results.stream().map(result -> {
                RetrievedSegment original = usable.get(result.index());
                // 分开保存两阶段得分，避免将模型相关性与 hybrid 分数混用。
                return RetrievedSegment.builder().id(original.getId()).text(original.getText())
                        .metadata(original.getMetadata()).score(original.getScore())
                        .rerankScore(result.score()).build();
            }).toList();
            log.info("文档重排完成，model={}, candidates={}, selected={}",
                    properties.getRerank().getModel(), usable.size(), ranked.size());
            return ranked;
        } catch (RuntimeException ex) {
            // 不记录异常正文，防止上游 HTTP 错误携带密钥或业务原文。
            log.warn("文档重排失败，回退混合召回顺序，errorType={}", ex.getClass().getSimpleName());
            return fallback;
        }
    }
}
