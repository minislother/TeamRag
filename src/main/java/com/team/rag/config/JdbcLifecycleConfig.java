package com.team.rag.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;

/**
 * Database infrastructure used when document lifecycle data is persisted in JDBC.
 */
@Configuration
@EnableTransactionManagement
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.lifecycle", name = "repository", havingValue = "jdbc")
public class JdbcLifecycleConfig {

    private final RagProperties properties;

    /**
     * Creates an explicit DataSource so local memory mode does not require database config.
     */
    @Bean
    public DataSource ragLifecycleDataSource() {
        RagProperties.Datasource datasource = properties.getDatasource();
        if (!StringUtils.hasText(datasource.getUrl())) {
            throw new IllegalStateException("rag.datasource.url is required when rag.lifecycle.repository=jdbc");
        }
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(datasource.getUrl());
        dataSource.setUsername(datasource.getUsername());
        dataSource.setPassword(datasource.getPassword());
        dataSource.setDriverClassName(datasource.getDriverClassName());
        return dataSource;
    }

    @Bean
    public JdbcTemplate ragLifecycleJdbcTemplate(@Qualifier("ragLifecycleDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public PlatformTransactionManager ragLifecycleTransactionManager(@Qualifier("ragLifecycleDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
