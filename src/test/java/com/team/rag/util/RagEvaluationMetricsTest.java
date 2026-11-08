package com.team.rag.util;

import com.team.rag.bean.RetrievedSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RAG 离线评测指标单元测试")
class RagEvaluationMetricsTest {

    private final RagEvaluationMetrics metrics = new RagEvaluationMetrics();

    @Test
    @DisplayName("评估检索结果 - 命中标准片段时计算召回率和准确率")
    void testEvaluateRetrieval_WithRelevantHits_CalculateMetrics() {
        // Given
        List<String> expectedIds = List.of("chunk-a", "chunk-c");
        List<RetrievedSegment> retrievedSegments = List.of(
                segment("chunk-x"),
                segment("chunk-a"),
                segment("chunk-c")
        );

        // When
        RagEvaluationMetrics.RetrievalMetrics result = metrics.evaluateRetrieval(expectedIds, retrievedSegments, 3);

        // Then
        assertThat(result.getTopK()).isEqualTo(3);
        assertThat(result.getRelevantHits()).isEqualTo(2);
        assertThat(result.getRecallAtK()).isEqualTo(1.0D);
        assertThat(result.getPrecisionAtK()).isCloseTo(2.0D / 3.0D, withinOffset());
        assertThat(result.getHitAtK()).isEqualTo(1.0D);
        assertThat(result.getMrr()).isEqualTo(0.5D);
        assertThat(result.getNdcgAtK()).isBetween(0.0D, 1.0D);
    }

    @Test
    @DisplayName("评估检索结果 - 重复命中不会重复计算相关片段")
    void testEvaluateRetrieval_DuplicateHit_CountOnce() {
        // Given
        List<String> expectedIds = List.of("chunk-a");
        List<RetrievedSegment> retrievedSegments = List.of(
                segment("chunk-a"),
                segment("chunk-a"),
                segment("chunk-b")
        );

        // When
        RagEvaluationMetrics.RetrievalMetrics result = metrics.evaluateRetrieval(expectedIds, retrievedSegments, 3);

        // Then
        assertThat(result.getRelevantHits()).isEqualTo(1);
        assertThat(result.getRecallAtK()).isEqualTo(1.0D);
        assertThat(result.getPrecisionAtK()).isCloseTo(1.0D / 3.0D, withinOffset());
        assertThat(result.getMrr()).isEqualTo(1.0D);
    }

    @Test
    @DisplayName("评估检索结果 - 无标准答案时返回零指标")
    void testEvaluateRetrieval_EmptyExpectedIds_ReturnZeroMetrics() {
        // Given
        List<RetrievedSegment> retrievedSegments = List.of(segment("chunk-a"));

        // When
        RagEvaluationMetrics.RetrievalMetrics result = metrics.evaluateRetrieval(List.of(), retrievedSegments, 5);

        // Then
        assertThat(result.getTopK()).isEqualTo(5);
        assertThat(result.getExpectedCount()).isZero();
        assertThat(result.getRetrievedCount()).isEqualTo(1);
        assertThat(result.getRelevantHits()).isZero();
        assertThat(result.getRecallAtK()).isZero();
        assertThat(result.getPrecisionAtK()).isZero();
        assertThat(result.getHitAtK()).isZero();
        assertThat(result.getMrr()).isZero();
        assertThat(result.getNdcgAtK()).isZero();
    }

    private RetrievedSegment segment(String id) {
        return RetrievedSegment.builder().id(id).text("片段 " + id).build();
    }

    private org.assertj.core.data.Offset<Double> withinOffset() {
        return org.assertj.core.data.Offset.offset(0.000001D);
    }
}
