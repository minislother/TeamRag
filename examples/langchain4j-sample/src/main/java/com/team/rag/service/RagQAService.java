package com.team.rag.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.streaming.StreamingChatModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.pinecone.PineconeEmbeddingStore;
import com.team.rag.util.BM25Retriever;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG问答服务：混合检索（BM25+向量）+ 大模型答案生成
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagQAService {

    private final BM25Retriever bm25Retriever;
    private final EmbeddingModel embeddingModel;
    private final PineconeEmbeddingStore pineconeEmbeddingStore;
    private final StreamingChatModel streamingChatModel;

    // 向量检索返回前K条
    @Value("${rag.hybrid-retrieval.vector-top-k}")
    private Integer vectorTopK;
    // 向量检索最小相似度阈值
    @Value("${rag.vector-retrieval.min-score}")
    private Double vectorMinScore;
    // 混合检索最终返回前K条
    @Value("${rag.hybrid-retrieval.final-top-k}")
    private Integer finalTopK;

    /**
     * RAG混合检索问答（流式输出）
     * @param query 用户问题
     * @param memoryId 对话ID（可选，用于聊天记忆）
     * @return 流式答案
     */
    public Flux<String> qa(String query, Long memoryId) {
        log.info("开始RAG混合检索问答，查询词：{}，对话ID：{}", query, memoryId);
        // 1. 混合检索：BM25关键词检索 + 向量语义检索
        List<TextSegment> bm25Segments = bm25Retriever.retrieve(query);
        List<TextSegment> vectorSegments = vectorRetrieve(query);
        // 合并去重，按相关性取前K条
        List<TextSegment> finalSegments = mergeSegments(bm25Segments, vectorSegments);

        if (finalSegments.isEmpty()) {
            log.warn("混合检索未匹配到任何相关片段，直接调用大模型回答");
            return streamingChatModel.stream("请回答问题：" + query);
        }

        // 2. 拼接检索结果为提示词（核心：将知识库内容传给大模型，避免幻觉）
        String prompt = buildPrompt(query, finalSegments);
        log.info("生成大模型提示词，拼接{}个相关片段", finalSegments.size());

        // 3. 调用流式大模型生成答案
        return streamingChatModel.stream(prompt);
    }

    /**
     * 向量语义检索
     */
    private List<TextSegment> vectorRetrieve(String query) {
        log.info("开始向量语义检索，查询词：{}，返回前{}条，最小相似度：{}", query, vectorTopK, vectorMinScore);
        // 向量化查询词
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        // 构建检索请求
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(vectorTopK)
                .minScore(vectorMinScore)
                .build();
        // 从Pinecone检索
        return pineconeEmbeddingStore.search(request).matches().stream()
                .map(match -> match.embedded())
                .collect(Collectors.toList());
    }

    /**
     * 合并BM25和向量检索结果，去重并取前K条
     */
    private List<TextSegment> mergeSegments(List<TextSegment> bm25, List<TextSegment> vector) {
        List<TextSegment> all = new ArrayList<>();
        all.addAll(bm25);
        // 去重：根据文本内容去重
        vector.forEach(v -> {
            if (!all.stream().anyMatch(a -> a.text().equals(v.text()))) {
                all.add(v);
            }
        });
        // 取前K条
        return all.stream().limit(finalTopK).collect(Collectors.toList());
    }

    /**
     * 构建大模型提示词：拼接检索结果+用户问题，引导大模型基于知识库回答，避免幻觉
     */
    private String buildPrompt(String query, List<TextSegment> segments) {
        StringBuilder context = new StringBuilder();
        context.append("以下是相关的团队知识库内容，请基于这些内容回答问题，若内容中无答案，请说明“知识库中未找到相关答案”，不要编造：\n");
        for (int i = 0; i < segments.size(); i++) {
            context.append(String.format("%d、%s\n", i+1, segments.get(i).text()));
        }
        context.append("\n用户问题：").append(query);
        return context.toString();
    }
}
