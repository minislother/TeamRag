package com.team.rag.util;

import com.team.rag.bean.DocumentSegment;
import com.team.rag.client.DashScopeClient;
import com.team.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 语义分块工具。
 *
 * <p>参考固定 token 分块的基础做法，但在相邻基础片段之间计算 embedding 余弦相似度，
 * 相似度高且未超过块长度时合并，从而减少团队规范、项目资料里的语义断裂。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticSplitter {

    private final DashScopeClient dashScopeClient;
    private final RagProperties properties;

    /**
     * 对文档文本做语义分块。
     */
    public List<DocumentSegment> split(String documentId, String text, Map<String, Object> baseMetadata) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        List<String> baseSegments = splitToBaseSegments(text);
        if (baseSegments.size() <= 1) {
            return toDocumentSegments(documentId, baseSegments, baseMetadata);
        }

        List<float[]> embeddings = dashScopeClient.embed(baseSegments);
        List<String> merged = new ArrayList<>();
        StringBuilder currentBlock = new StringBuilder(baseSegments.get(0));

        for (int i = 1; i < baseSegments.size(); i++) {
            String next = baseSegments.get(i);
            double similarity = cosine(embeddings.get(i - 1), embeddings.get(i));
            boolean canMerge = similarity >= properties.getSemanticSplitter().getSimilarityThreshold()
                    && currentBlock.length() + next.length() <= properties.getSemanticSplitter().getMaxChars();
            if (canMerge) {
                currentBlock.append("\n").append(next);
            } else {
                merged.add(currentBlock.toString());
                currentBlock = new StringBuilder(addOverlap(merged.get(merged.size() - 1), next));
            }
        }
        merged.add(currentBlock.toString());
        log.info("文档 {} 完成语义分块，基础片段 {} 个，语义块 {} 个", documentId, baseSegments.size(), merged.size());
        return toDocumentSegments(documentId, merged, baseMetadata);
    }

    /**
     * 先按较小长度切出基础片段，为后续 embedding 相似度合并做准备。
     */
    private List<String> splitToBaseSegments(String text) {
        int maxChars = Math.max(properties.getSemanticSplitter().getMaxChars() / 3, 300);
        List<String> units = splitByParagraphAndSentence(text);
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String unit : units) {
            if (!StringUtils.hasText(unit)) {
                continue;
            }
            if (current.length() + unit.length() > maxChars && current.length() > 0) {
                result.add(current.toString().trim());
                current = new StringBuilder();
            }
            if (unit.length() > maxChars) {
                result.addAll(splitLongText(unit, maxChars));
            } else {
                current.append(unit).append("\n");
            }
        }
        if (current.length() > 0) {
            result.add(current.toString().trim());
        }
        return result;
    }

    /**
     * 按段落和中文句末标点拆分文本，尽量保留自然语义边界。
     */
    private List<String> splitByParagraphAndSentence(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> result = new ArrayList<>();
        for (String paragraph : normalized.split("\\n{2,}")) {
            String trimmed = paragraph.trim();
            if (!StringUtils.hasText(trimmed)) {
                continue;
            }
            String[] sentences = trimmed.split("(?<=[。！？!?；;])");
            for (String sentence : sentences) {
                if (StringUtils.hasText(sentence)) {
                    result.add(sentence.trim());
                }
            }
        }
        return result;
    }

    /**
     * 对超过基础长度限制的长文本做硬切分，避免单块过大。
     */
    private List<String> splitLongText(String text, int maxChars) {
        List<String> result = new ArrayList<>();
        int offset = 0;
        while (offset < text.length()) {
            int end = Math.min(offset + maxChars, text.length());
            result.add(text.substring(offset, end));
            offset = end;
        }
        return result;
    }

    /**
     * 将上一片段尾部拼到下一片段前面，保留跨块上下文。
     */
    private String addOverlap(String previous, String next) {
        int overlap = Math.max(properties.getSemanticSplitter().getOverlapChars(), 0);
        if (overlap == 0 || previous.length() <= overlap) {
            return next;
        }
        return previous.substring(previous.length() - overlap) + "\n" + next;
    }

    /**
     * 把切分后的纯文本块包装成可写入 Pinecone 的文档片段对象。
     */
    private List<DocumentSegment> toDocumentSegments(String documentId, List<String> segments, Map<String, Object> baseMetadata) {
        List<DocumentSegment> result = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            Map<String, Object> metadata = new LinkedHashMap<>(baseMetadata);
            metadata.put("documentId", documentId);
            metadata.put("segmentIndex", i);
            metadata.put("text", segments.get(i));
            String version = String.valueOf(metadata.getOrDefault("version", "1"));
            String vectorId = "doc:" + documentId + ":" + version + ":" + i;
            metadata.put("vectorId", vectorId);
            result.add(DocumentSegment.builder()
                    .id(vectorId)
                    .documentId(documentId)
                    .index(i)
                    .text(segments.get(i))
                    .metadata(metadata)
                    .build());
        }
        return result;
    }

    /**
     * 计算两个 embedding 向量的余弦相似度，用于判断相邻片段是否合并。
     */
    private double cosine(float[] left, float[] right) {
        if (left.length != right.length) {
            return 0.0;
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
