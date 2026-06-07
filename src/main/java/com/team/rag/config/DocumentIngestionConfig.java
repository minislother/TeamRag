package com.team.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 文档异步入库线程池配置。
 */
@Configuration
public class DocumentIngestionConfig {

    /**
     * 文档解析、分块、向量化和 Pinecone 写入会放在后台线程执行。
     */
    @Bean
    public TaskExecutor documentIngestionTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("rag-ingest-");
        executor.initialize();
        return executor;
    }
}
