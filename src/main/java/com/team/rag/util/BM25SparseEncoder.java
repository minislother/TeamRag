package com.team.rag.util;

import com.team.rag.bean.SparseVector;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量 BM25 / sparse 编码器。
 *
 * <p>Pinecone hybrid search 需要稀疏向量。这里把英文、数字、代码 token 和中文二元片段
 * 编成稳定 hash index，并用词频归一化作为 sparse value。生产环境可替换为 IK 分词器。</p>
 */
@Component
public class BM25SparseEncoder {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-zA-Z0-9_#./:-]+|[\\p{IsHan}]{1,}");
    private static final int HASH_BUCKETS = 1_000_000;

    /**
     * 把文本编码成 Pinecone sparse vector。
     */
    public SparseVector encode(String text) {
        List<String> tokens = tokenize(text);
        Map<Integer, Double> termFrequency = new HashMap<>();
        for (String token : tokens) {
            int index = stableIndex(token);
            termFrequency.merge(index, 1.0, Double::sum);
        }
        int length = Math.max(tokens.size(), 1);
        List<Integer> indices = new ArrayList<>(termFrequency.keySet());
        indices.sort(Integer::compareTo);
        List<Double> values = indices.stream()
                .map(index -> Math.log1p(termFrequency.get(index)) / Math.sqrt(length))
                .toList();
        return new SparseVector(indices, values);
    }

    /**
     * 文本分词。中文按 unigram + bigram，兼顾“缓存穿透”这类连续术语。
     */
    public List<String> tokenize(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text.toLowerCase());
        while (matcher.find()) {
            String token = matcher.group();
            if (token.chars().allMatch(ch -> Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN)) {
                addChineseTokens(token, tokens);
            } else {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /**
     * 为连续中文文本补充 unigram 和 bigram token，提升关键词匹配召回。
     */
    private void addChineseTokens(String text, List<String> tokens) {
        for (int i = 0; i < text.length(); i++) {
            tokens.add(String.valueOf(text.charAt(i)));
            if (i + 1 < text.length()) {
                tokens.add(text.substring(i, i + 2));
            }
        }
    }

    /**
     * 将 token 稳定映射到 Pinecone sparse vector 的固定 hash 桶。
     */
    private int stableIndex(String token) {
        byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
        int hash = 0x811C9DC5;
        for (byte item : bytes) {
            hash ^= item & 0xff;
            hash *= 0x01000193;
        }
        return Math.floorMod(hash, HASH_BUCKETS);
    }
}
