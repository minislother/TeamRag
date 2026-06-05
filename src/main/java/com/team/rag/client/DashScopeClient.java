package com.team.rag.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阿里百炼 OpenAI 兼容接口客户端。
 *
 * <p>用于调用 text-embedding-v3 向量模型和 glm-5 对话模型。
 * 项目复用 RepoPilot 的 .env 时，会优先读取 REPOPILOT_GLM_API_KEY 作为 DashScope 密钥。</p>
 */
@RequiredArgsConstructor
public class DashScopeClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final RagProperties properties;

    /**
     * 批量向量化文本。
     */
    public List<float[]> embed(List<String> texts) {
        requireApiKey();
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.getDashscope().getEmbeddingModel());
        payload.put("input", texts);
        if (properties.getDashscope().getEmbeddingDimensions() != null) {
            payload.put("dimensions", properties.getDashscope().getEmbeddingDimensions());
        }

        JsonNode root = webClient.post()
                .uri(normalizeBaseUrl() + "/embeddings")
                .headers(headers -> headers.setBearerAuth(properties.getDashscope().getApiKey()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        if (root == null || !root.has("data")) {
            throw new IllegalStateException("DashScope embedding 响应为空");
        }
        List<float[]> vectors = new ArrayList<>();
        for (JsonNode item : root.get("data")) {
            vectors.add(toFloatArray(item.get("embedding")));
        }
        return vectors;
    }

    /**
     * 单条文本向量化。
     */
    public float[] embedOne(String text) {
        List<float[]> vectors = embed(List.of(text));
        if (vectors.isEmpty()) {
            throw new IllegalStateException("DashScope embedding 返回空向量");
        }
        return vectors.get(0);
    }

    /**
     * 非流式生成，主要用于查询重写和摘要。
     */
    public String complete(String prompt) {
        requireApiKey();
        Map<String, Object> payload = chatPayload(prompt, false);
        JsonNode root = webClient.post()
                .uri(normalizeBaseUrl() + "/chat/completions")
                .headers(headers -> headers.setBearerAuth(properties.getDashscope().getApiKey()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
        return extractChatContent(root);
    }

    /**
     * 流式生成答案，返回增量文本 Flux。
     */
    public Flux<String> stream(String prompt) {
        requireApiKey();
        Map<String, Object> payload = chatPayload(prompt, Boolean.TRUE.equals(properties.getDashscope().getStreamEnabled()));
        if (!Boolean.TRUE.equals(payload.get("stream"))) {
            return Flux.just(complete(prompt));
        }
        return webClient.post()
                .uri(normalizeBaseUrl() + "/chat/completions")
                .headers(headers -> headers.setBearerAuth(properties.getDashscope().getApiKey()))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToFlux(String.class)
                .flatMapIterable(this::parseSseChunk)
                .filter(StringUtils::hasText);
    }

    /**
     * 组装 DashScope OpenAI 兼容 chat/completions 请求体。
     */
    private Map<String, Object> chatPayload(String prompt, boolean stream) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.getDashscope().getChatModel());
        payload.put("temperature", properties.getDashscope().getTemperature());
        payload.put("stream", stream);
        payload.put("messages", List.of(
                Map.of("role", "system", "content", "你是团队知识库助手，只能基于给定上下文回答。"),
                Map.of("role", "user", "content", prompt)
        ));
        return payload;
    }

    /**
     * 从非流式聊天响应中提取首个候选答案文本。
     */
    private String extractChatContent(JsonNode root) {
        if (root == null || !root.has("choices") || root.get("choices").isEmpty()) {
            return "";
        }
        JsonNode message = root.get("choices").get(0).get("message");
        return message == null ? "" : message.path("content").asText("");
    }

    /**
     * 解析 DashScope SSE 分片，提取增量输出文本。
     */
    private List<String> parseSseChunk(String chunk) {
        List<String> deltas = new ArrayList<>();
        for (String rawLine : chunk.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.equals("data: [DONE]") || line.equals("[DONE]")) {
                continue;
            }
            if (line.startsWith("data:")) {
                line = line.substring("data:".length()).trim();
            }
            if (!line.startsWith("{")) {
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(line);
                JsonNode delta = root.path("choices").path(0).path("delta").path("content");
                if (!delta.isMissingNode()) {
                    deltas.add(delta.asText(""));
                    continue;
                }
                JsonNode message = root.path("choices").path(0).path("message").path("content");
                if (!message.isMissingNode()) {
                    deltas.add(message.asText(""));
                }
            } catch (Exception ignored) {
                // SSE 分片可能被 WebClient 按非 JSON 边界切开，无法解析时跳过当前碎片。
            }
        }
        return deltas;
    }

    /**
     * 将 JSON embedding 数组转换为 Java float 向量。
     */
    private float[] toFloatArray(JsonNode embedding) {
        if (embedding == null || !embedding.isArray()) {
            throw new IllegalStateException("DashScope embedding 字段缺失");
        }
        float[] vector = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            vector[i] = (float) embedding.get(i).asDouble();
        }
        return vector;
    }

    /**
     * 规范化 DashScope base URL，避免后续拼接路径时出现双斜杠。
     */
    private String normalizeBaseUrl() {
        String baseUrl = properties.getDashscope().getBaseUrl();
        if (!StringUtils.hasText(baseUrl)) {
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * 在真正调用 DashScope 前检查 API Key 是否已从配置或 .env 注入。
     */
    private void requireApiKey() {
        if (!StringUtils.hasText(properties.getDashscope().getApiKey())) {
            throw new IllegalStateException("缺少 DashScope API Key，请在 .env 中配置 DASH_SCOPE_API_KEY 或复用 REPOPILOT_GLM_API_KEY");
        }
    }
}
