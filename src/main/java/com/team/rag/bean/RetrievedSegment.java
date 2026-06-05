package com.team.rag.bean;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 检索命中的知识片段。
 */
@Data
@Builder
public class RetrievedSegment {

    private String id;
    private String text;
    private Double score;
    private Map<String, Object> metadata;
}
