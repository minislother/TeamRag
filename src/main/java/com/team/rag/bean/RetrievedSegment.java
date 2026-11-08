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
    /** 专用模型相关性分数；禁用或降级时为空，score 仍是原始召回分数。 */
    private Double rerankScore;
    private Map<String, Object> metadata;
}
