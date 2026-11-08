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
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("原始查询与改写查询融合测试")
class QueryFusionServiceTest {
    @Mock private BM25Retriever retriever;

    @Test
    @DisplayName("融合 - 互补结果合并并限制候选数量")
    void testRetrieve_FuseComplementaryResults() {
        // Given
        RagProperties properties = new RagProperties();
        properties.getQueryFusion().setCandidateCount(3);
        QueryFusionService service = new QueryFusionService(retriever, properties);
        RetrievedSegment a = segment("a"), b = segment("b"), c = segment("c"), d = segment("d");
        when(retriever.retrieve(eq("原问题"), eq(3), anyString(), anyMap())).thenReturn(List.of(a, b, c));
        when(retriever.retrieve(eq("改写问题"), eq(3), anyString(), anyMap())).thenReturn(List.of(d, b, a));
        // When
        List<RetrievedSegment> result = service.retrieve("原问题", "改写问题", 3, "ns", Map.of());
        // Then
        assertThat(result).hasSize(3).extracting(RetrievedSegment::getId).containsExactly("b", "a", "d");
        verify(retriever).retrieve("原问题", 3, "ns", Map.of());
        verify(retriever).retrieve("改写问题", 3, "ns", Map.of());
    }

    @Test
    @DisplayName("融合关闭 - 仅使用改写查询保持兼容")
    void testRetrieve_Disabled_UseRewrittenOnly() {
        // Given
        RagProperties properties = new RagProperties();
        properties.getQueryFusion().setEnabled(false);
        QueryFusionService service = new QueryFusionService(retriever, properties);
        when(retriever.retrieve(eq("改写问题"), eq(12), anyString(), anyMap())).thenReturn(List.of(segment("b")));
        // When
        List<RetrievedSegment> result = service.retrieve("原问题", "改写问题", null, "ns", Map.of());
        // Then
        assertThat(result).extracting(RetrievedSegment::getId).containsExactly("b");
        verify(retriever, never()).retrieve(eq("原问题"), anyInt(), anyString(), anyMap());
    }

    @Test
    @DisplayName("融合 - 相同查询不重复召回")
    void testRetrieve_SameQuery_SingleCall() {
        // Given
        RagProperties properties = new RagProperties();
        QueryFusionService service = new QueryFusionService(retriever, properties);
        when(retriever.retrieve(eq("同一个问题"), eq(12), anyString(), anyMap())).thenReturn(List.of(segment("a")));
        // When
        service.retrieve("同一个问题", "同一个问题", 1, "ns", Map.of());
        // Then
        verify(retriever, times(1)).retrieve(eq("同一个问题"), eq(12), anyString(), anyMap());
    }

    private RetrievedSegment segment(String id) {
        return RetrievedSegment.builder().id(id).text(id).score(0.5).metadata(Map.of("documentId", id)).build();
    }
}
