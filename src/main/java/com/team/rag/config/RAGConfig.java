package com.team.rag.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.tika.Tika;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * RAG 基础 Bean 配置。
 */
@Configuration
public class RAGConfig {

    /**
     * 创建供 DashScope 和 Pinecone 客户端复用的 WebClient。
     */
    @Bean
    public WebClient ragWebClient(ObjectMapper objectMapper) {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(120));
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    /**
     * 创建 Apache Tika 实例，用于解析上传文档文本。
     */
    @Bean
    public Tika tika() {
        return new Tika();
    }
}
