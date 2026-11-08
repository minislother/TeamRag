package com.team.rag;

import com.team.rag.config.DotenvApplicationListener;
import com.team.rag.config.RagProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * baituan 社团资料 RAG 知识库启动类。
 *
 * <p>启动时先加载项目根目录下的 .env，便于复用 RepoPilot 原来的模型 API 配置。</p>
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(RagProperties.class)
public class TeamRagApplication {

    /**
     * 启动 Spring Boot 应用，并在配置绑定前加载项目根目录的 .env。
     */
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(TeamRagApplication.class);
        application.addListeners(new DotenvApplicationListener());
        application.run(args);
    }
}
