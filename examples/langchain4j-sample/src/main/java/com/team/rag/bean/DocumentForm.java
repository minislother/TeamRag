package com.team.rag.bean;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档上传入参
 */
@Data
public class DocumentForm {
    /** 文档文件（支持PDF/Word/MD/TXT） */
    private MultipartFile file;
    /** 文档类型（如：项目资料/开发规范/新人培训/问题排查） */
    private String docType;
    /** 文档描述（可选） */
    private String description;
}
