package com.team.rag.bean;

import lombok.Builder;
import lombok.Data;

/**
 * 文档入库任务展示对象。
 */
@Data
@Builder
public class IngestionJobResponse {

    private String jobId;
    private String documentId;
    private Integer version;
    private String status;
    private Integer progress;
    private String errorMessage;
    private Integer retryCount;
    private String createdAt;
    private String updatedAt;
}
