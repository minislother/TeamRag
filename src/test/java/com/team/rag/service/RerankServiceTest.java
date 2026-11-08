package com.team.rag.service;

import com.team.rag.bean.RetrievedSegment;
import com.team.rag.client.DashScopeClient;
import com.team.rag.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("召回后重排服务测试")
class RerankServiceTest {
    @Mock private DashScopeClient client;
    private RagProperties properties;
    private RerankService service;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        service = new RerankService(client, properties);
    }

    @Test
    @DisplayName("重排 - 采用模型顺序并保留原始分数和权限元数据")
    void testRerank_Success_PreserveMetadata() {
        // Given
        RetrievedSegment a = segment("a"), b = segment("b");
        when(client.rerank("问题", List.of("a", "b"), 1))
                .thenReturn(List.of(new DashScopeClient.RerankResult(1, 0.98)));
        // When
        List<RetrievedSegment> result = service.rerank("问题", List.of(a, b), 1);
        // Then
        assertThat(result).extracting(RetrievedSegment::getId).containsExactly("b");
        assertThat(result.get(0).getMetadata()).isEqualTo(b.getMetadata());
        assertThat(result.get(0).getScore()).isEqualTo(0.4);
        assertThat(result.get(0).getRerankScore()).isEqualTo(0.98);
        assertThat(b.getRerankScore()).isNull();
        verify(client).rerank("问题", List.of("a", "b"), 1);
    }

    @Test
    @DisplayName("重排 - 上游失败时按原召回顺序截取")
    void testRerank_Failure_Fallback() {
        // Given
        when(client.rerank("问题", List.of("a", "b"), 1)).thenThrow(new IllegalStateException());
        // When
        List<RetrievedSegment> result = service.rerank("问题", List.of(segment("a"), segment("b")), 1);
        // Then
        assertThat(result).extracting(RetrievedSegment::getId).containsExactly("a");
        assertThat(result.get(0).getRerankScore()).isNull();
        verify(client).rerank("问题", List.of("a", "b"), 1);
    }

    @Test
    @DisplayName("重排 - 关闭后不扩召回也不调用模型")
    void testRerank_Disabled_NoRemoteCall() {
        // Given
        properties.getRerank().setEnabled(false);
        // When
        List<RetrievedSegment> result = service.rerank("问题", List.of(segment("a"), segment("b")), 1);
        // Then
        assertThat(service.candidateCount(1)).isEqualTo(1);
        assertThat(result).extracting(RetrievedSegment::getId).containsExactly("a");
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("重排 - 空候选和单条有效候选跳过模型")
    void testRerank_EmptyOrSingle_Skip() {
        // Given
        List<RetrievedSegment> candidates = Arrays.asList(null, segment(" "), segment("a"));
        // When
        List<RetrievedSegment> result = service.rerank("问题", candidates, null);
        // Then
        assertThat(result).extracting(RetrievedSegment::getId).containsExactly("a");
        assertThat(service.rerank("问题", null, null)).isEmpty();
        assertThat(service.rerank("问题", List.of(), null)).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("候选数量 - 默认值和上下界均受控")
    void testCandidateCount_Bounds() {
        // Given
        properties.getRerank().setCandidateCount(12);
        // When & Then
        assertThat(service.finalTopK(null)).isEqualTo(6);
        assertThat(service.finalTopK(-1)).isEqualTo(6);
        assertThat(service.candidateCount(3)).isEqualTo(12);
        assertThat(service.candidateCount(20)).isEqualTo(20);
        assertThat(service.candidateCount(Integer.MAX_VALUE)).isEqualTo(100);
        verifyNoInteractions(client);
    }

    private RetrievedSegment segment(String text) {
        return RetrievedSegment.builder().id(text).text(text).score(0.4)
                .metadata(Map.of("project", "club", "status", "ACTIVE")).build();
    }
}
