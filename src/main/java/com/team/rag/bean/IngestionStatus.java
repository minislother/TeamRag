package com.team.rag.bean;

/**
 * 文档入库任务状态。
 */
public enum IngestionStatus {

    /** 任务已创建，等待后台线程消费。 */
    PENDING,

    /** 任务正在执行解析、分块、向量化和写入 Pinecone。 */
    RUNNING,

    /** 任务执行成功，新版本已经切换为 ACTIVE。 */
    SUCCESS,

    /** 任务执行失败，错误原因会记录在任务里。 */
    FAILED
}
