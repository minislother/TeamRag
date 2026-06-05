package com.team.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * 团队RAG知识库项目启动类
 * 排除DataSourceAutoConfiguration：若未使用MySQL，可排除，避免启动报错
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class TeamRagApplication {
    public static void main(String[] args) {
        SpringApplication.run(TeamRagApplication.class, args);
    }
}
