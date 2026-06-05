package com.team.rag.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.client.PineconeClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Pinecone 向量存储配置。
 */
@Configuration
public class PineconeConfig {

    /**
     * 装配 Pinecone REST 客户端，供文档入库和 hybrid query 使用。
     */
    @Bean
    public PineconeClient pineconeClient(WebClient ragWebClient, ObjectMapper objectMapper, RagProperties properties) {
        return new PineconeClient(ragWebClient, objectMapper, properties);
    }
}
