package com.team.rag.bean;

import lombok.Data;

/**
 * RAG问答入参
 */
@Data
public class RagQo {
    /** 用户查询问题 */
    private String query;
    /** 对话ID（可选，用于聊天记忆） */
    private Long memoryId;
}
