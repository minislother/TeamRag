package com.team.rag.service;

/**
 * 已保存到本地磁盘的上传文件信息。
 */
public record StoredDocumentFile(String path, String fileHash, long size) {
}
