package com.team.rag.bean;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档上传入参。
 *
 * <p>前端上传资料时，可以同时标注文档类型、所属项目和标签。
 * 这些元数据会写入 Pinecone metadata，用于后续过滤和提升召回质量。</p>
 */
@Data
public class DocumentForm {

    /** 文档文件，支持 PDF / Word / Markdown / TXT / HTML 等 Tika 可解析格式。 */
    private MultipartFile file;

    /** 文档类型，例如：项目资料、研发规范、新人培训、问题排查。 */
    private String docType;

    /** 所属项目，例如：支付系统、用户中心、数据平台。 */
    private String project;

    /** 逗号分隔标签，例如：接口、缓存、规范。 */
    private String tags;

    /** 文档描述，可选。 */
    private String description;
}
