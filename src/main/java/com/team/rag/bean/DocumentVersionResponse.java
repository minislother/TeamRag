package com.team.rag.bean;

import lombok.Builder;
import lombok.Data;

/**
 * 文档版本展示对象。
 */
@Data
@Builder
public class DocumentVersionResponse {

    private Integer version;
    private String status;
    private Integer segmentCount;
    private String fileHash;
    private String errorMessage;
    private String createdAt;
    private String activatedAt;
}
