package com.team.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从用户话语中提取可长期复用的偏好、约束和明确要求，并按结构保存。
 * 只保存带有明确提示词的内容，避免把普通问题或模型回答误写入长期记忆。
 */
@Slf4j
@Service
public class UserLongTermMemoryService {
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final Map<String, List<String>> fallback = new ConcurrentHashMap<>();

    @Autowired
    public UserLongTermMemoryService(ObjectMapper objectMapper, @Autowired(required = false) JdbcTemplate jdbcTemplate) {
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    public void extractAndStore(String userId, String text) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(text)) return;
        for (String fact : extract(text)) {
            fallback.computeIfAbsent(userId, ignored -> new ArrayList<>()).add(fact);
            if (jdbcTemplate != null) {
                try {
                    jdbcTemplate.update("INSERT INTO rag_user_memories (id,user_id,memory_type,memory_text,source) VALUES (?,?,?,?,?)",
                            UUID.randomUUID().toString(), userId, typeOf(fact), fact, "explicit-user-message");
                } catch (Exception ex) {
                    log.debug("长期用户记忆 JDBC 写入失败，保留进程内副本: {}", ex.getMessage());
                }
            }
        }
    }

    private List<String> extract(String text) {
        List<String> facts = new ArrayList<>();
        String[] markers = {"我喜欢", "我偏好", "我的偏好是", "请记住", "记住我", "以后请", "不要", "必须"};
        for (String marker : markers) {
            int start = text.indexOf(marker);
            if (start >= 0) {
                String fact = text.substring(start).trim();
                if (fact.length() > marker.length() && fact.length() <= 500) facts.add(fact);
            }
        }
        return facts.stream().distinct().toList();
    }

    private String typeOf(String fact) {
        if (fact.contains("喜欢") || fact.contains("偏好")) return "preference";
        if (fact.contains("不要") || fact.contains("必须")) return "constraint";
        return "instruction";
    }
}
