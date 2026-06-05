package com.team.rag.util;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentSplitter;
import dev.langchain4j.data.document.splitter.TokenCountDocumentSplitter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.tokenizer.Tokenizer;
import dev.langchain4j.model.tokenizer.huggingface.HuggingFaceTokenizer;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义分块工具：基于余弦相似度的动态分块，解决固定分块语义断裂问题
 * 步骤：1.初步按句子/段落切分 2.向量化计算相似度 3.高于阈值则合并 4.最终生成语义完整的块
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticSplitter {

    private final EmbeddingModel embeddingModel;
    private final Tokenizer tokenizer = new HuggingFaceTokenizer();

    // 语义分块相似度阈值
    @Value("${rag.semantic-splitter.similarity-threshold}")
    private Double similarityThreshold;
    // 单块最大token数
    @Value("${rag.semantic-splitter.max-tokens}")
    private Integer maxTokens;
    // 块间重叠token
    @Value("${rag.semantic-splitter.overlap-tokens}")
    private Integer overlapTokens;

    /**
     * 对文档进行语义分块
     * @param document 原始文档
     * @return 语义完整的文本块列表
     */
    public List<TextSegment> split(Document document) {
        log.info("开始对文档[{}]进行语义分块，相似度阈值：{}，最大token：{}", document.metadata().get("name"), similarityThreshold, maxTokens);
        // 步骤1：初步切分（按token切分为小片段，作为语义合并的基础）
        DocumentSplitter baseSplitter = new TokenCountDocumentSplitter(maxTokens/4, 0, tokenizer);
        List<TextSegment> baseSegments = baseSplitter.split(document);
        if (baseSegments.size() <= 1) {
            return baseSegments; // 只有一个片段，无需合并
        }

        // 步骤2：对所有基础片段向量化
        List<Embedding> embeddings = new ArrayList<>();
        for (TextSegment segment : baseSegments) {
            Embedding embedding = embeddingModel.embed(segment.text()).content();
            embeddings.add(embedding);
        }

        // 步骤3：基于余弦相似度合并片段，生成语义块
        List<TextSegment> semanticSegments = new ArrayList<>();
        StringBuilder currentBlock = new StringBuilder(baseSegments.get(0).text());
        int currentTokenCount = tokenizer.estimateTokenCount(currentBlock.toString());

        for (int i = 1; i < baseSegments.size(); i++) {
            TextSegment currentSeg = baseSegments.get(i-1);
            TextSegment nextSeg = baseSegments.get(i);
            Embedding currentEmb = embeddings.get(i-1);
            Embedding nextEmb = embeddings.get(i);

            // 计算余弦相似度
            double similarity = calculateCosineSimilarity(currentEmb.vector(), nextEmb.vector());
            int nextTokenCount = tokenizer.estimateTokenCount(nextSeg.text());

            // 相似度高于阈值 且 合并后不超过最大token数 → 合并
            if (similarity >= similarityThreshold && (currentTokenCount + nextTokenCount) <= maxTokens) {
                currentBlock.append("\n").append(nextSeg.text());
                currentTokenCount += nextTokenCount;
            } else {
                // 不满足条件，保存当前块，开始新块（添加重叠部分保证上下文）
                semanticSegments.add(TextSegment.from(addOverlap(currentBlock.toString(), nextSeg.text())));
                currentBlock = new StringBuilder(nextSeg.text());
                currentTokenCount = nextTokenCount;
            }
        }

        // 保存最后一个块
        semanticSegments.add(TextSegment.from(currentBlock.toString()));
        log.info("文档[{}]语义分块完成，原始{}个基础片段，生成{}个语义块", document.metadata().get("name"), baseSegments.size(), semanticSegments.size());
        return semanticSegments;
    }

    /**
     * 计算余弦相似度
     * @param vec1 向量1
     * @param vec2 向量2
     * @return 余弦相似度（0-1，越高越相似）
     */
    private double calculateCosineSimilarity(float[] vec1, float[] vec2) {
        if (vec1.length != vec2.length) {
            throw new IllegalArgumentException("向量维度不一致，无法计算相似度");
        }
        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;
        for (int i = 0; i < vec1.length; i++) {
            dotProduct += vec1[i] * vec2[i];
            norm1 += Math.pow(vec1[i], 2);
            norm2 += Math.pow(vec2[i], 2);
        }
        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    /**
     * 为块添加重叠部分，保证上下文连贯
     * @param currentText 当前块文本
     * @param nextText 下一个块文本
     * @return 添加重叠后的文本
     */
    private String addOverlap(String currentText, String nextText) {
        if (overlapTokens <= 0) {
            return currentText;
        }
        // 取nextText的前N个token作为重叠部分，拼接到currentText末尾
        List<String> nextTokens = tokenizer.tokenize(nextText);
        int overlapNum = Math.min(overlapTokens, nextTokens.size());
        StringBuilder overlap = new StringBuilder();
        for (int i = 0; i < overlapNum; i++) {
            overlap.append(nextTokens.get(i)).append(" ");
        }
        return currentText + "\n" + overlap;
    }
}
