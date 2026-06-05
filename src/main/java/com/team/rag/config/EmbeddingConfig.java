package com.team.rag.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.client.DashScopeClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 阿里百炼 text-embedding-v3 / glm-5 客户端配置。
 */
@Configuration
public class EmbeddingConfig {

    /**
     * 装配 DashScope 客户端，集中处理 embedding 和 glm-5 调用。
     */
    @Bean
    public DashScopeClient dashScopeClient(WebClient ragWebClient, ObjectMapper objectMapper, RagProperties properties) {
        return new DashScopeClient(ragWebClient, objectMapper, properties);
    }
}
