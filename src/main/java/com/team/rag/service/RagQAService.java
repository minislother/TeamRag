package com.team.rag.service;

import com.team.rag.bean.ChatMessage;
import com.team.rag.bean.RagQo;
import com.team.rag.bean.RetrievedSegment;
import com.team.rag.client.DashScopeClient;
import com.team.rag.config.RagProperties;
import com.team.rag.util.BM25Retriever;
import com.team.rag.util.PromptBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * RAG 问答服务：短期记忆 + 查询重写 + 长期记忆 + Pinecone 混合检索 + 流式回答。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagQAService {

    private final ShortTermMemoryService shortTermMemoryService;
    private final QueryRewriteService queryRewriteService;
    private final LongTermMemoryService longTermMemoryService;
    private final BM25Retriever bm25Retriever;
    private final PromptBuilder promptBuilder;
    private final DashScopeClient dashScopeClient;
    private final RagProperties properties;

    /**
     * RAG 混合检索问答，流式输出。
     */
    public Flux<String> qa(RagQo qo) {
        String query = qo.getQuery();
        String conversationId = conversationId(qo);
        List<ChatMessage> shortMemory = shortTermMemoryService.getRecentMessages(conversationId);
        String rewrittenQuery = queryRewriteService.rewrite(query, shortMemory);
        List<RetrievedSegment> longMemory = longTermMemoryService.recall(rewrittenQuery);
        List<RetrievedSegment> ragSegments = bm25Retriever.retrieve(
                rewrittenQuery,
                qo.getTopK(),
                properties.getPinecone().getNamespace()
        );
        boolean generalFallback = shouldUseGeneralFallback(longMemory, ragSegments);
        String prompt = generalFallback
                ? promptBuilder.buildGeneralFallback(query, rewrittenQuery, shortMemory)
                : promptBuilder.build(query, rewrittenQuery, shortMemory, longMemory, ragSegments);
        log.info("RAG 问答开始，conversationId={}, query={}, rewritten={}, ragHits={}, memoryHits={}, generalFallback={}",
                conversationId, query, rewrittenQuery, ragSegments.size(), longMemory.size(), generalFallback);

        StringBuilder answer = new StringBuilder();
        Flux<String> answerFlux = dashScopeClient.stream(prompt);
        if (generalFallback && StringUtils.hasText(properties.getQa().getGeneralFallbackPrefix())) {
            answerFlux = Flux.concat(Flux.just(properties.getQa().getGeneralFallbackPrefix()), answerFlux);
        }
        return answerFlux
                .doOnNext(answer::append)
                .doOnComplete(() -> shortTermMemoryService.appendExchange(conversationId, query, answer.toString()));
    }

    /**
     * 知识库和长期记忆均无命中时，按配置转通用大模型兜底。
     */
    private boolean shouldUseGeneralFallback(List<RetrievedSegment> longMemory, List<RetrievedSegment> ragSegments) {
        return Boolean.TRUE.equals(properties.getQa().getGeneralFallbackEnabled())
                && !hasUsableSegment(longMemory)
                && !hasUsableSegment(ragSegments);
    }

    /**
     * 判断检索结果里是否存在可写入 Prompt 的有效文本片段。
     */
    private boolean hasUsableSegment(List<RetrievedSegment> segments) {
        return segments != null && segments.stream()
                .map(RetrievedSegment::getText)
                .anyMatch(StringUtils::hasText);
    }

    /**
     * 统一解析会话标识，兼容 conversationId 和示例代码里的 memoryId。
     */
    private String conversationId(RagQo qo) {
        if (StringUtils.hasText(qo.getConversationId())) {
            return qo.getConversationId();
        }
        if (qo.getMemoryId() != null) {
            return String.valueOf(qo.getMemoryId());
        }
        return "default";
    }
}
