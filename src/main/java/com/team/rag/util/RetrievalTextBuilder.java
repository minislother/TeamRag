package com.team.rag.util;

import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 为检索和重排构造带来源上下文的文本；原始正文仍单独保存，避免展示时混入技术字段。
 */
public final class RetrievalTextBuilder {
    private static final String[] FIELDS = {
            "documentTitle", "filename", "docType", "tags", "headingPath"
    };

    private RetrievalTextBuilder() {
    }

    public static String build(String text, Map<String, Object> metadata) {
        StringBuilder context = new StringBuilder();
        if (metadata != null) {
            for (String field : FIELDS) {
                Object value = metadata.get(field);
                if (value != null && StringUtils.hasText(String.valueOf(value))) {
                    context.append(field).append(": ").append(value).append('\n');
                }
            }
        }
        if (context.length() == 0) {
            return text;
        }
        return context.append("content:\n").append(text == null ? "" : text).toString();
    }
}
