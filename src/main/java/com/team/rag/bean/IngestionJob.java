package com.team.rag.bean;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 文档异步入库任务。
 */
@Data
@Builder
public class IngestionJob {

    private String id;
    private String documentId;
    private Integer version;
    private IngestionStatus status;
    private Integer progress;
    private String errorMessage;
    private Integer retryCount;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
