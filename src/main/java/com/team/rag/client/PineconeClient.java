package com.team.rag.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.team.rag.bean.RetrievedSegment;
import com.team.rag.bean.SparseVector;
import com.team.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pinecone REST 客户端。
 *
 * <p>负责向量 upsert 和 dense + sparse hybrid query。若未配置 PINECONE_HOST，
 * 会通过控制面接口根据 index 名称自动查询 host。</p>
 */
@RequiredArgsConstructor
public class PineconeClient {

    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {
    };

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final RagProperties properties;
    private volatile String cachedHost;

    /**
     * 批量写入文档语义块。
     */
    public void upsert(String namespace, List<VectorRecord> records) {
        requireConfig();
        if (records == null || records.isEmpty()) {
            return;
        }
        List<Map<String, Object>> vectors = records.stream().map(record -> {
            Map<String, Object> vector = new LinkedHashMap<>();
            vector.put("id", record.id());
            vector.put("values", toDoubleList(record.denseVector()));
            vector.put("metadata", record.metadata());
            if (record.sparseVector() != null && !record.sparseVector().getIndices().isEmpty()) {
                vector.put("sparseValues", Map.of(
                        "indices", record.sparseVector().getIndices(),
                        "values", record.sparseVector().getValues()
                ));
            }
            return vector;
        }).toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("namespace", namespace);
        payload.put("vectors", vectors);

        webClient.post()
                .uri(dataPlaneUrl() + "/vectors/upsert")
                .headers(headers -> headers.add("Api-Key", properties.getPinecone().getApiKey()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
    }

    /**
     * 混合检索：dense vector 负责语义召回，sparse vector 负责关键词/BM25 召回。
     */
    public List<RetrievedSegment> query(String namespace, float[] denseVector, SparseVector sparseVector, int topK, double alpha, double minScore) {
        requireConfig();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("namespace", namespace);
        payload.put("topK", topK);
        payload.put("includeMetadata", true);
        payload.put("vector", scaleDense(denseVector, alpha));
        if (sparseVector != null && !sparseVector.getIndices().isEmpty()) {
            payload.put("sparseVector", Map.of(
                    "indices", sparseVector.getIndices(),
                    "values", sparseVector.getValues().stream().map(value -> value * (1.0 - alpha)).toList()
            ));
        }

        JsonNode root = webClient.post()
                .uri(dataPlaneUrl() + "/query")
                .headers(headers -> headers.add("Api-Key", properties.getPinecone().getApiKey()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        List<RetrievedSegment> result = new ArrayList<>();
        JsonNode matches = root == null ? null : root.get("matches");
        if (matches == null || !matches.isArray()) {
            return result;
        }
        for (JsonNode match : matches) {
            double score = match.path("score").asDouble(0.0);
            if (score < minScore) {
                continue;
            }
            JsonNode metadata = match.path("metadata");
            Map<String, Object> meta = objectMapper.convertValue(metadata, METADATA_TYPE);
            result.add(RetrievedSegment.builder()
                    .id(match.path("id").asText())
                    .score(score)
                    .text(metadata.path("text").asText(""))
                    .metadata(meta)
                    .build());
        }
        return result;
    }

    /**
     * 生成 Pinecone data plane API 的基础地址。
     */
    private String dataPlaneUrl() {
        String host = resolveHost();
        return host.startsWith("http") ? host : "https://" + host;
    }

    /**
     * 优先使用显式 host，否则通过 Pinecone 控制面查询 index host 并缓存。
     */
    private String resolveHost() {
        if (StringUtils.hasText(properties.getPinecone().getHost())) {
            return trimTrailingSlash(properties.getPinecone().getHost());
        }
        if (StringUtils.hasText(cachedHost)) {
            return cachedHost;
        }
        JsonNode root = webClient.get()
                .uri("https://api.pinecone.io/indexes/" + properties.getPinecone().getIndex())
                .headers(headers -> headers.add("Api-Key", properties.getPinecone().getApiKey()))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
        String host = root == null ? "" : root.path("host").asText("");
        if (!StringUtils.hasText(host)) {
            throw new IllegalStateException("无法解析 Pinecone host，请在 .env 中配置 PINECONE_HOST");
        }
        cachedHost = trimTrailingSlash(host);
        return cachedHost;
    }

    /**
     * 在访问 Pinecone 前确认必要的 index 和 API Key 配置存在。
     */
    private void requireConfig() {
        if (!StringUtils.hasText(properties.getPinecone().getApiKey())) {
            throw new IllegalStateException("缺少 PINECONE_API_KEY");
        }
        if (!StringUtils.hasText(properties.getPinecone().getIndex())) {
            throw new IllegalStateException("缺少 PINECONE_INDEX");
        }
    }

    /**
     * 去掉 URL 或 host 末尾斜杠，统一后续路径拼接。
     */
    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * 将 float 向量转换为 JSON 序列化更直接的 Double 列表。
     */
    private List<Double> toDoubleList(float[] vector) {
        List<Double> values = new ArrayList<>(vector.length);
        for (float item : vector) {
            values.add((double) item);
        }
        return values;
    }

    /**
     * 按 hybrid alpha 缩放 dense 向量，使 Pinecone 融合 dense/sparse 权重。
     */
    private List<Double> scaleDense(float[] vector, double alpha) {
        List<Double> values = new ArrayList<>(vector.length);
        for (float item : vector) {
            values.add(item * alpha);
        }
        return values;
    }

    /**
     * Pinecone 单条向量记录。
     */
    public record VectorRecord(String id, float[] denseVector, SparseVector sparseVector, Map<String, Object> metadata) {
    }
}
