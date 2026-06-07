package com.team.rag.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * Stores original uploaded files and opens them later for async ingestion.
 */
public interface DocumentStorageService {

    StoredDocumentFile store(MultipartFile file, String documentId, Integer version);

    InputStream open(String path) throws IOException;

    void deleteQuietly(StoredDocumentFile storedFile);
}
