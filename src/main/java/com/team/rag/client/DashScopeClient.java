package com.team.rag.client;

import com.team.rag.config.RagProperties;
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
 * <p>示例代码通过 LangChain4j 的模型对象调用 embedding 和 streaming chat。
 * 当前项目默认 chat model 是 glm-5，所以这里使用 LangChain4j 的 OpenAI-compatible
 * 模型类承接示例流程，同时保留现有 base URL / API Key 配置。</p>
 */
@RequiredArgsConstructor
public class DashScopeClient {

    private final RagProperties properties;

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
     * 流式生成答案；RAG 最终回答和示例代码一样走流式大模型。
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
     * 创建 LangChain4j embedding model，对应示例里的 text-embedding-v3。
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
     * 创建流式 chat model，对应示例代码里的 streamingChatModel。
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
