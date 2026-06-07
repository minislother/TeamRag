CREATE TABLE rag_documents (
    id VARCHAR(64) PRIMARY KEY,
    filename VARCHAR(512) NOT NULL,
    doc_type VARCHAR(128),
    project VARCHAR(128),
    tags VARCHAR(512),
    description TEXT,
    current_version INT,
    status VARCHAR(32),
    created_at TIMESTAMP NULL,
    updated_at TIMESTAMP NULL,
    INDEX idx_rag_documents_project (project),
    INDEX idx_rag_documents_status (status),
    INDEX idx_rag_documents_updated_at (updated_at)
);

CREATE TABLE rag_document_versions (
    document_id VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    id VARCHAR(128) NOT NULL,
    file_hash VARCHAR(128),
    file_path VARCHAR(1024),
    status VARCHAR(32),
    segment_count INT,
    error_message TEXT,
    created_at TIMESTAMP NULL,
    activated_at TIMESTAMP NULL,
    PRIMARY KEY (document_id, version),
    INDEX idx_rag_versions_status (status),
    INDEX idx_rag_versions_file_hash (file_hash)
);

CREATE TABLE rag_ingestion_jobs (
    id VARCHAR(64) PRIMARY KEY,
    document_id VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    status VARCHAR(32),
    progress INT,
    error_message TEXT,
    retry_count INT,
    created_at TIMESTAMP NULL,
    updated_at TIMESTAMP NULL,
    INDEX idx_rag_jobs_document_id (document_id),
    INDEX idx_rag_jobs_status (status),
    INDEX idx_rag_jobs_updated_at (updated_at)
);

CREATE TABLE rag_document_chunks (
    id VARCHAR(160) PRIMARY KEY,
    document_id VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    chunk_index INT NOT NULL,
    vector_id VARCHAR(160),
    text LONGTEXT,
    metadata_json LONGTEXT,
    created_at TIMESTAMP NULL,
    INDEX idx_rag_chunks_doc_version (document_id, version),
    INDEX idx_rag_chunks_vector_id (vector_id)
);
