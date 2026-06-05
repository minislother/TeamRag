package com.team.rag.bean;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * RAG 问答入参。
 */
@Data
public class RagQo {

    /** 用户查询问题。 */
    @NotBlank(message = "query 不能为空")
    private String query;

    /** 会话 ID，用于短期记忆；未传时默认使用 default。 */
    private String conversationId;

    /** 兼容参考代码里的 memoryId 字段。 */
    private Long memoryId;

    /** 所属项目，可用于后续 metadata 过滤。 */
    private String project;

    /** 返回片段数，可选。 */
    private Integer topK;
}
