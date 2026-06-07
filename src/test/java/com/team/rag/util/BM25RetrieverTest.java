package com.team.rag.util;

import com.team.rag.bean.RetrievedSegment;
import com.team.rag.bean.SparseVector;
import com.team.rag.client.DashScopeClient;
import com.team.rag.client.PineconeClient;
import com.team.rag.config.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@DisplayName("BM25 混合检索工具单元测试")
@ExtendWith(MockitoExtension.class)
class BM25RetrieverTest {

    @Mock
    private BM25SparseEncoder sparseEncoder;
    @Mock
    private DashScopeClient dashScopeClient;
    @Mock
    private PineconeClient pineconeClient;

    @Test
    @DisplayName("执行混合检索 - 使用 sparse 和 dense 向量查询 Pinecone")
    void testRetrieve_Success_QueryPinecone() {
        // Given
        RagProperties properties = new RagProperties();
        BM25Retriever retriever = new BM25Retriever(sparseEncoder, dashScopeClient, pineconeClient, properties);
        SparseVector sparseVector = new SparseVector(List.of(1), List.of(0.8));
        float[] denseVector = new float[]{0.1f, 0.2f};
        RetrievedSegment segment = RetrievedSegment.builder().text("Redis 缓存穿透").build();
        when(sparseEncoder.encode("Redis 缓存穿透")).thenReturn(sparseVector);
        when(dashScopeClient.embedOne("Redis 缓存穿透")).thenReturn(denseVector);
        when(pineconeClient.query(
                eq("team-rag-documents"),
                eq(denseVector),
                eq(sparseVector),
                eq(properties.getHybrid().getTopK()),
                eq(properties.getHybrid().getAlpha()),
                eq(properties.getHybrid().getMinScore()),
                eq(Map.of())
        )).thenReturn(List.of(segment));

        // When
        List<RetrievedSegment> result = retriever.retrieve("Redis 缓存穿透", null, "team-rag-documents");

        // Then
        assertThat(result).containsExactly(segment);
    }

    @Test
    @DisplayName("执行混合检索 - 自定义 topK 优先生效")
    void testRetrieve_CustomTopK_UseRequestTopK() {
        // Given
        RagProperties properties = new RagProperties();
        BM25Retriever retriever = new BM25Retriever(sparseEncoder, dashScopeClient, pineconeClient, properties);
        SparseVector sparseVector = new SparseVector(List.of(1), List.of(0.8));
        float[] denseVector = new float[]{0.1f, 0.2f};
        when(sparseEncoder.encode("Redis")).thenReturn(sparseVector);
        when(dashScopeClient.embedOne("Redis")).thenReturn(denseVector);
        when(pineconeClient.query(
                eq("team-rag-documents"),
                eq(denseVector),
                eq(sparseVector),
                eq(2),
                eq(properties.getHybrid().getAlpha()),
                eq(properties.getHybrid().getMinScore()),
                eq(Map.of())
        )).thenReturn(List.of());

        // When
        List<RetrievedSegment> result = retriever.retrieve("Redis", 2, "team-rag-documents");

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("执行混合检索 - 透传 metadata filter")
    void testRetrieve_WithFilter_PassToPinecone() {
        // Given
        RagProperties properties = new RagProperties();
        BM25Retriever retriever = new BM25Retriever(sparseEncoder, dashScopeClient, pineconeClient, properties);
        SparseVector sparseVector = new SparseVector(List.of(1), List.of(0.8));
        float[] denseVector = new float[]{0.1f, 0.2f};
        Map<String, Object> filter = Map.of("status", Map.of("$eq", "ACTIVE"));
        when(sparseEncoder.encode("Redis")).thenReturn(sparseVector);
        when(dashScopeClient.embedOne("Redis")).thenReturn(denseVector);
        when(pineconeClient.query(
                eq("team-rag-documents"),
                eq(denseVector),
                eq(sparseVector),
                eq(properties.getHybrid().getTopK()),
                eq(properties.getHybrid().getAlpha()),
                eq(properties.getHybrid().getMinScore()),
                eq(filter)
        )).thenReturn(List.of());

        // When
        List<RetrievedSegment> result = retriever.retrieve("Redis", null, "team-rag-documents", filter);

        // Then
        assertThat(result).isEmpty();
    }
}
