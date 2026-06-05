package com.team.rag.bean;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 文档语义块。
 */
@Data
@Builder
public class DocumentSegment {

    private String id;
    private String documentId;
    private Integer index;
    private String text;
    private Map<String, Object> metadata;
}
