package com.team.rag.config;

import dev.langchain4j.model.dashscope.DashScopeEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里百炼text-embedding-v3向量模型配置
 */
@Configuration
public class EmbeddingConfig {

    @Value("${langchain4j.community.dashscope.embedding-model.api-key}")
    private String dashScopeApiKey;

    @Value("${langchain4j.community.dashscope.embedding-model.model-name}")
    private String embeddingModelName;

    /**
     * 初始化向量模型，用于文本向量化、语义分块的相似度计算
     */
    @Bean
    public DashScopeEmbeddingModel embeddingModel() {
        return DashScopeEmbeddingModel.builder()
                .apiKey(dashScopeApiKey)
                .modelName(embeddingModelName)
                .build();
    }
}
