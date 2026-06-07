package com.team.rag.bean;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 团队知识库中的文档主记录。
 */
@Data
@Builder
public class RagDocument {

    private String id;
    private String filename;
    private String docType;
    private String project;
    private String tags;
    private String description;
    private Integer currentVersion;
    private DocumentStatus status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
