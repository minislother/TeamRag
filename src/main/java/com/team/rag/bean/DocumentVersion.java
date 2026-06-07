package com.team.rag.bean;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 同一个文档的一个具体版本。
 */
@Data
@Builder
public class DocumentVersion {

    private String id;
    private String documentId;
    private Integer version;
    private String fileHash;
    private String filePath;
    private DocumentStatus status;
    private Integer segmentCount;
    private String errorMessage;
    private OffsetDateTime createdAt;
    private OffsetDateTime activatedAt;
}
