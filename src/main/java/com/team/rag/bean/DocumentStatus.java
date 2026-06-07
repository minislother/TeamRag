package com.team.rag.bean;

/**
 * 文档版本在生产入库流程中的生命周期状态。
 */
public enum DocumentStatus {

    /** 新版本正在解析、分块、向量化或写入 Pinecone。 */
    PROCESSING,

    /** 当前可被 RAG 检索召回的有效版本。 */
    ACTIVE,

    /** 已被新版本替代，后续可异步清理 Pinecone 向量。 */
    SUPERSEDED,

    /** 入库失败，需要查看任务错误并重试。 */
    FAILED,

    /** 文档已被删除，不应再参与检索。 */
    DELETED
}
