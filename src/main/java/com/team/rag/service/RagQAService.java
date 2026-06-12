package com.team.rag.service;

import com.team.rag.bean.ChatMessage;
import com.team.rag.bean.DocumentStatus;
import com.team.rag.bean.RagQo;
import com.team.rag.bean.RetrievedSegment;
import com.team.rag.client.DashScopeClient;
import com.team.rag.config.RagProperties;
import com.team.rag.security.AccessControlService;
import com.team.rag.util.BM25Retriever;
import com.team.rag.util.PromptBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 问答服务：短期记忆 + 查询重写 + 长期记忆 + Pinecone 混合检索 + Prompt 增强 + 大模型流式回答。
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
    private final AccessControlService accessControlService;

    /**
     * RAG 混合检索问答，流式输出。
     */
    public Flux<String> qa(RagQo qo) {
        String query = qo.getQuery();
        String conversationId = conversationId(qo);

        // 先取出本轮会话最近的问答，后面的查询重写会用它把“继续说一下”这类问题补全。
        List<ChatMessage> shortMemory = shortTermMemoryService.getRecentMessages(conversationId);

        // 查询重写只用于检索，不改变用户问题本身；这样能提高 Pinecone 的召回质量。
        String rewrittenQuery = queryRewriteService.rewrite(query, shortMemory);

        Map<String, Object> projectFilter = projectAccessFilter(qo);

        // 长期记忆是用户或团队长期沉淀的信息；生产开启权限时也要按项目隔离。
        List<RetrievedSegment> longMemory = longTermMemoryService.recall(rewrittenQuery, projectFilter);

        // RAG 的“检索”步骤：用改写后的问题去 Pinecone 做 BM25 + 向量混合检索。
        List<RetrievedSegment> ragSegments = bm25Retriever.retrieve(
                rewrittenQuery,
                qo.getTopK(),
                properties.getPinecone().getNamespace(),
                activeDocumentFilter(projectFilter)
        );

        // 只有真正检索到可用文本时才构造 RAG Prompt；无命中时走通用大模型兜底。
        boolean knowledgeHit = promptBuilder.hasUsableKnowledge(longMemory) || promptBuilder.hasUsableKnowledge(ragSegments);
        String prompt = knowledgeHit
                ? promptBuilder.build(query, rewrittenQuery, shortMemory, longMemory, ragSegments)
                : promptBuilder.buildGeneral(query, rewrittenQuery, shortMemory);
        log.info("RAG 问答开始，conversationId={}, query={}, rewritten={}, ragHits={}, memoryHits={}, knowledgeHit={}",
                conversationId, query, rewrittenQuery, ragSegments.size(), longMemory.size(), knowledgeHit);

        StringBuilder answer = new StringBuilder();
        return dashScopeClient.stream(prompt)
                .doOnNext(answer::append)
                // 模型流式输出结束后，把本轮问答写回短期记忆，供下一轮追问使用。
                .doOnComplete(() -> shortTermMemoryService.appendExchange(conversationId, query, answer.toString()));
    }

    /**
     * 统一解析会话标识，兼容 conversationId 和旧前端传入的 memoryId。
     */
    private String conversationId(RagQo qo) {
        if (StringUtils.hasText(qo.getConversationId())) {
            return qo.getConversationId();
        }
        if (qo.getMemoryId() != null) {
            return String.valueOf(qo.getMemoryId());
        }
        return "1";
    }

    /**
     * 生产检索默认只召回 ACTIVE 文档版本，避免旧版本、失败版本或删除文档进入 Prompt。
     */
    private Map<String, Object> activeDocumentFilter(Map<String, Object> projectFilter) {
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("status", Map.of("$eq", DocumentStatus.ACTIVE.name()));
        filter.putAll(projectFilter);
        return filter;
    }

    private Map<String, Object> projectAccessFilter(RagQo qo) {
        Map<String, Object> filter = new LinkedHashMap<>();
        if (StringUtils.hasText(qo.getProject())) {
            accessControlService.requireProjectAccess(qo.getProject().trim());
            filter.put("project", Map.of("$eq", qo.getProject().trim()));
            return filter;
        }
        List<String> restrictedProjects = accessControlService.restrictedProjects();
        if (!restrictedProjects.isEmpty()) {
            filter.put("project", Map.of("$in", restrictedProjects));
        }
        return filter;
    }
}
