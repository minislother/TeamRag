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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@DisplayName("长期记忆召回服务单元测试")
@ExtendWith(MockitoExtension.class)
class LongTermMemoryServiceTest {

    @Mock
    private BM25Retriever bm25Retriever;

    @Test
    @DisplayName("召回长期记忆 - 成功返回检索结果")
    void testRecall_Success_ReturnSegments() {
        // Given
        RagProperties properties = new RagProperties();
        LongTermMemoryService service = new LongTermMemoryService(bm25Retriever, properties);
        RetrievedSegment segment = RetrievedSegment.builder().text("团队约定使用 Redis 空值缓存").build();
        when(bm25Retriever.retrieve(eq("Redis 缓存穿透"), eq(3), eq(properties.getPinecone().getMemoryNamespace())))
                .thenReturn(List.of(segment));

        // When
        List<RetrievedSegment> result = service.recall("Redis 缓存穿透");

        // Then
        assertThat(result).containsExactly(segment);
    }

    @Test
    @DisplayName("召回长期记忆 - 检索异常时返回空列表")
    void testRecall_RetrieverError_ReturnEmptyList() {
        // Given
        RagProperties properties = new RagProperties();
        LongTermMemoryService service = new LongTermMemoryService(bm25Retriever, properties);
        when(bm25Retriever.retrieve(eq("Redis 缓存穿透"), eq(3), eq(properties.getPinecone().getMemoryNamespace())))
                .thenThrow(new RuntimeException("pinecone error"));

        // When
        List<RetrievedSegment> result = service.recall("Redis 缓存穿透");

        // Then
        assertThat(result).isEmpty();
    }
}
