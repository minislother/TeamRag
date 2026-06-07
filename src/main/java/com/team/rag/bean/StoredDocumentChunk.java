package com.team.rag.bean;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 已入库的文档分块记录。
 */
@Data
@Builder
public class StoredDocumentChunk {

    private String id;
    private String documentId;
    private Integer version;
    private Integer chunkIndex;
    private String vectorId;
    private String text;
    private Map<String, Object> metadata;
    private OffsetDateTime createdAt;
}
