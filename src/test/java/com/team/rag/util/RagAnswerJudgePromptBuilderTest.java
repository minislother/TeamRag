package com.team.rag.util;

import com.team.rag.bean.RetrievedSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RAG 答案评测 Prompt 构建器单元测试")
class RagAnswerJudgePromptBuilderTest {

    private final RagAnswerJudgePromptBuilder builder = new RagAnswerJudgePromptBuilder();

    @Test
    @DisplayName("构建答案评测 Prompt - 有检索片段时输出评分字段和证据")
    void testBuild_WithSupportingSegments_AppendEvidenceAndCriteria() {
        // Given
        RetrievedSegment segment = RetrievedSegment.builder()
                .id("chunk-1")
                .score(0.91D)
                .text("缓存穿透可以通过缓存空值和布隆过滤器处理。")
                .build();

        // When
        String prompt = builder.build(
                "Redis 缓存穿透怎么处理",
                "可以缓存空值，也可以使用布隆过滤器。",
                List.of(segment)
        );

        // Then
        assertThat(prompt)
                .contains("faithfulnessScore")
                .contains("answerRelevanceScore")
                .contains("citationCoverageScore")
                .contains("hallucinationRisk")
                .contains("id=chunk-1")
                .contains("缓存穿透可以通过缓存空值和布隆过滤器处理。");
    }

    @Test
    @DisplayName("构建答案评测 Prompt - 无检索片段时要求检查未命中声明")
    void testBuild_NoSupportingSegments_CheckKnowledgeMissDisclaimer() {
        // Given
        String answer = "社团资料知识库未命中，以下回答基于通用知识，仅供参考。\n可以使用布隆过滤器。";

        // When
        String prompt = builder.build("Redis 缓存穿透怎么处理", answer, List.of());

        // Then
        assertThat(prompt)
                .contains("检索片段：\n无")
                .contains("如果检索片段为空")
                .contains("社团资料知识库未命中")
                .contains("不要引入外部知识");
    }
}
