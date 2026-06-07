package com.team.rag.service;

import com.team.rag.bean.RetrievedSegment;
import com.team.rag.config.RagProperties;
import com.team.rag.util.BM25Retriever;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@DisplayName("Long term memory service tests")
@ExtendWith(MockitoExtension.class)
class LongTermMemoryServiceTest {

    @Mock
    private BM25Retriever bm25Retriever;

    @Test
    @DisplayName("Recall long term memory - returns retriever hits")
    void testRecall_Success_ReturnSegments() {
        // Given
        RagProperties properties = new RagProperties();
        LongTermMemoryService service = new LongTermMemoryService(bm25Retriever, properties);
        RetrievedSegment segment = RetrievedSegment.builder().text("team uses Redis null-value cache").build();
        when(bm25Retriever.retrieve(eq("Redis cache penetration"), eq(3), eq(properties.getPinecone().getMemoryNamespace()), anyMap()))
                .thenReturn(List.of(segment));

        // When
        List<RetrievedSegment> result = service.recall("Redis cache penetration");

        // Then
        assertThat(result).containsExactly(segment);
    }

    @Test
    @DisplayName("Recall long term memory - returns empty list when retriever fails")
    void testRecall_RetrieverError_ReturnEmptyList() {
        // Given
        RagProperties properties = new RagProperties();
        LongTermMemoryService service = new LongTermMemoryService(bm25Retriever, properties);
        when(bm25Retriever.retrieve(eq("Redis cache penetration"), eq(3), eq(properties.getPinecone().getMemoryNamespace()), anyMap()))
                .thenThrow(new RuntimeException("pinecone error"));

        // When
        List<RetrievedSegment> result = service.recall("Redis cache penetration");

        // Then
        assertThat(result).isEmpty();
    }
}
