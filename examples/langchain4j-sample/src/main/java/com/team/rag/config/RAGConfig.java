package com.team.rag.config;

import dev.langchain4j.model.dashscope.DashScopeStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG核心配置：大模型、流式输出等
 */
@Configuration
public class RAGConfig {

    @Value("${langchain4j.community.dashscope.streaming-chat-model.api-key}")
    private String dashScopeApiKey;

    @Value("${langchain4j.community.dashscope.streaming-chat-model.model-name}")
    private String chatModelName;

    @Value("${langchain4j.community.dashscope.streaming-chat-model.temperature}")
    private Double temperature;

    /**
     * 初始化流式大模型，用于最终答案生成，降低温度系数减少幻觉
     */
    @Bean
    public DashScopeStreamingChatModel streamingChatModel() {
        return DashScopeStreamingChatModel.builder()
                .apiKey(dashScopeApiKey)
                .modelName(chatModelName)
                .temperature(temperature)
                .build();
    }
}
