CREATE TABLE rag_user_memories (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    memory_type VARCHAR(32) NOT NULL,
    memory_text VARCHAR(1000) NOT NULL,
    source VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_rag_user_memories_user (user_id),
    INDEX idx_rag_user_memories_type (memory_type)
);
