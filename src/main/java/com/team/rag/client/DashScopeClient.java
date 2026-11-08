package com.team.rag.client;

import com.team.rag.config.RagProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.MediaType;
import java.time.Duration;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Comparator;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 阿里百炼 DashScope 模型客户端。
 *
 * <p>当前项目默认 chat model 是 glm-5，因此这里使用 LangChain4j 的 OpenAI-compatible
 * 模型类封装 embedding、非流式 chat 和 streaming chat 调用，同时保留现有 base URL / API Key 配置。</p>
 */
@RequiredArgsConstructor
public class DashScopeClient {

    private final RagProperties properties;
    private final WebClient webClient;

    /** 返回候选数组下标和相关性分数，绝不信任服务端返回的文档正文或元数据。 */
    public record RerankResult(int index, double score) {}

    /** 使用百炼原生重排协议；校验完整响应后再交给业务层映射回已授权的候选。 */
    public List<RerankResult> rerank(String query, List<String> documents, int topN) {
        requireApiKey();
        if (!StringUtils.hasText(query) || documents == null || documents.isEmpty() || topN <= 0) {
            throw new IllegalArgumentException("重排需要非空问题、候选文本和正数 topN");
        }
        RagProperties.Rerank config = properties.getRerank();
        int expected = Math.min(topN, documents.size());
        JsonNode response = webClient.post().uri(config.getUrl())
                .headers(headers -> headers.setBearerAuth(properties.getDashscope().getApiKey()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", config.getModel(),
                        "input", Map.of("query", query, "documents", documents),
                        "parameters", Map.of("top_n", expected, "return_documents", false)))
                .retrieve().bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(Math.max(1, config.getTimeoutSeconds())));
        JsonNode results = response == null ? null : response.path("output").path("results");
        if (results == null || !results.isArray() || results.size() != expected) {
            throw new IllegalStateException("重排响应缺少完整 results");
        }
        List<RerankResult> ranked = new ArrayList<>();
        HashSet<Integer> seen = new HashSet<>();
        for (JsonNode result : results) {
            JsonNode index = result.path("index");
            JsonNode score = result.path("relevance_score");
            if (!index.isIntegralNumber() || !index.canConvertToInt() || index.intValue() < 0
                    || index.intValue() >= documents.size() || !seen.add(index.intValue())
                    || !score.isNumber() || !Double.isFinite(score.doubleValue())
                    || score.doubleValue() < 0 || score.doubleValue() > 1) {
                throw new IllegalStateException("重排响应包含非法下标或分数");
            }
            ranked.add(new RerankResult(index.intValue(), score.doubleValue()));
        }
        ranked.sort(Comparator.comparingDouble(RerankResult::score).reversed());
        return ranked;
    }

    /**
     * 批量向量化文本；文档入库和问题检索都会先把文本变成向量。
     */
    public List<float[]> embed(List<String> texts) {
        requireApiKey();
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<TextSegment> segments = texts.stream()
                .map(TextSegment::from)
                .toList();
        return embeddingModel().embedAll(segments)
                .content()
                .stream()
                .map(Embedding::vector)
                .toList();
    }

    /**
     * 单条文本向量化，主要用于把用户问题转成查询向量。
     */
    public float[] embedOne(String text) {
        List<float[]> vectors = embed(List.of(text));
        if (vectors.isEmpty()) {
            throw new IllegalStateException("DashScope embedding 返回空向量");
        }
        return vectors.get(0);
    }

    /**
     * 非流式生成，主要用于查询重写和短期记忆摘要。
     */
    public String complete(String prompt) {
        requireApiKey();
        return chatModel().chat(prompt);
    }

    /**
     * 流式生成答案；RAG 最终回答默认走流式大模型。
     */
    public Flux<String> stream(String prompt) {
        if (!Boolean.TRUE.equals(properties.getDashscope().getStreamEnabled())) {
            return Flux.just(complete(prompt));
        }
        requireApiKey();
        return Flux.create(sink -> {
            try {
                streamingChatModel().chat(prompt, new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partialResponse) {
                        if (StringUtils.hasText(partialResponse)) {
                            sink.next(partialResponse);
                        }
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse completeResponse) {
                        sink.complete();
                    }

                    @Override
                    public void onError(Throwable error) {
                        sink.error(error);
                    }
                });
            } catch (Exception ex) {
                if (!sink.isCancelled()) {
                    sink.error(ex);
                }
            }
        });
    }

    /**
     * 创建 LangChain4j embedding model。
     */
    private OpenAiEmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(normalizeBaseUrl())
                .apiKey(properties.getDashscope().getApiKey())
                .modelName(properties.getDashscope().getEmbeddingModel())
                .dimensions(properties.getDashscope().getEmbeddingDimensions())
                .build();
    }

    /**
     * 创建非流式 chat model，供查询重写和摘要这些后台小任务使用。
     */
    private OpenAiChatModel chatModel() {
        return OpenAiChatModel.builder()
                .baseUrl(normalizeBaseUrl())
                .apiKey(properties.getDashscope().getApiKey())
                .modelName(properties.getDashscope().getChatModel())
                .temperature(properties.getDashscope().getTemperature())
                .build();
    }

    /**
     * 创建流式 chat model。
     */
    private OpenAiStreamingChatModel streamingChatModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(normalizeBaseUrl())
                .apiKey(properties.getDashscope().getApiKey())
                .modelName(properties.getDashscope().getChatModel())
                .temperature(properties.getDashscope().getTemperature())
                .build();
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
