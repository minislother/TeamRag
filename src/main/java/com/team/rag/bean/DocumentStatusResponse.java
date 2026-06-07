package com.team.rag.bean;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 文档状态展示对象。
 */
@Data
@Builder
public class DocumentStatusResponse {

    private String documentId;
    private String filename;
    private String docType;
    private String project;
    private String tags;
    private String description;
    private Integer currentVersion;
    private String status;
    private String createdAt;
    private String updatedAt;
    private List<DocumentVersionResponse> versions;
    private List<IngestionJobResponse> jobs;
}
