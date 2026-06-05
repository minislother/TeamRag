package com.team.rag.bean;

import lombok.Builder;
import lombok.Data;

/**
 * 文档上传入库结果。
 */
@Data
@Builder
public class DocumentUploadResponse {

    private String documentId;
    private String filename;
    private String docType;
    private String project;
    private Integer segmentCount;
    private String message;
}
