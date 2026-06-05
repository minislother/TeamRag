package com.team.rag.util;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pinecone.PineconeEmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.similarity.JaccardSimilarity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * BM25关键词检索工具：基于词频的精确检索，弥补向量检索专业术语、代码命令检索的不足
 * 与向量检索结合实现混合检索，提升团队知识库检索准确性
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BM25Retriever {

    private final PineconeEmbeddingStore pineconeEmbeddingStore;
    private final JaccardSimilarity jaccard = new JaccardSimilarity();

    // BM25返回前K条
    @Value("${rag.hybrid-retrieval.bm25-top-k}")
    private Integer bm25TopK;

    /**
     * BM25关键词检索
     * @param query 用户查询词
     * @return 按相关性排序的文本片段列表
     */
    public List<TextSegment> retrieve(String query) {
        log.info("开始BM25关键词检索，查询词：{}，返回前{}条", query, bm25TopK);
        // 1. 从Pinecone获取所有文本片段（生产环境可优化为按命名空间过滤）
        List<TextSegment> allSegments = getAllSegmentsFromPinecone();
        if (allSegments.isEmpty()) {
            log.warn("BM25检索：向量库中无文本片段");
            return Collections.emptyList();
        }

        // 2. 计算每个片段与查询词的BM25相似度
        Map<TextSegment, Double> scoreMap = new HashMap<>();
        for (TextSegment segment : allSegments) {
            double score = calculateBM25Score(query, segment.text());
            scoreMap.put(segment, score);
        }

        // 3. 按相似度降序排序，取前K条
        List<TextSegment> result = scoreMap.entrySet().stream()
                .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()))
                .limit(bm25TopK)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        log.info("BM25检索完成，匹配到{}条相关片段", result.size());
        return result;
    }

    /**
     * 计算BM25相似度（简化版，适配团队知识库轻量检索）
     * @param query 查询词
     * @param text 文本片段
     * @return BM25得分
     */
    private double calculateBM25Score(String query, String text) {
        // 分词（简单按空格/标点分词，生产环境可替换为IK分词器）
        Set<String> queryWords = splitToWords(query);
        Set<String> textWords = splitToWords(text);
        if (queryWords.isEmpty() || textWords.isEmpty()) {
            return 0.0;
        }

        // 计算词频、文档频率（简化版，使用杰卡德相似度替代核心BM25公式，易实现）
        double jaccardScore = jaccard.apply(query, text);
        // 词频加权：查询词在文本中出现的次数越多，得分越高
        int termFreq = 0;
        for (String word : queryWords) {
            termFreq += Collections.frequency(splitToWords(text), word);
        }
        return jaccardScore * (1 + Math.log1p(termFreq));
    }

    /**
     * 文本分词（简单实现，生产环境可替换为IK分词器/结巴分词，提升中文分词效果）
     */
    private Set<String> splitToWords(String text) {
        // 去除标点、转小写、按空格分割
        String cleanText = text.replaceAll("[\\pP\\pS]", " ").toLowerCase().trim();
        return new HashSet<>(Arrays.asList(cleanText.split("\\s+")));
    }

    /**
     * 从Pinecone获取所有文本片段（生产环境可优化为批量查询）
     */
    private List<TextSegment> getAllSegmentsFromPinecone() {
        // 此处为简化实现，生产环境可通过Pinecone的SDK批量查询所有vectors
        // 实际项目中可结合数据库记录文档ID，关联查询向量库
        List<TextSegment> segments = new ArrayList<>();
        // 注：PineconeEmbeddingStore无直接获取所有片段的方法，生产环境可做如下优化：
        // 1. 用MySQL记录文档分块后的segmentID、文本内容
        // 2. 检索时先从MySQL查BM25，再从Pinecone查向量，提升效率
        return segments;
    }
}
