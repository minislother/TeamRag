package com.team.rag.util;

import com.team.rag.bean.RetrievedSegment;
import com.team.rag.bean.ChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RAG 提示词构建器单元测试")
class PromptBuilderTest {

    private final PromptBuilder promptBuilder = new PromptBuilder();

    @Test
    @DisplayName("判断可用知识 - 空片段和空文本不会命中")
    void testHasUsableKnowledge_EmptySegment_ReturnFalse() {
        // Given
        RetrievedSegment blankSegment = RetrievedSegment.builder().text(" ").build();

        // When
        boolean result = promptBuilder.hasUsableKnowledge(Arrays.asList(null, blankSegment));

        // Then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("构建 RAG Prompt - 命中片段时使用编号格式")
    void testBuild_KnowledgeHit_UseNumberedPromptFormat() {
        // Given
        RetrievedSegment segment = RetrievedSegment.builder()
                .text("缓存穿透可以通过缓存空值和布隆过滤器解决。")
                .build();

        // When
        String prompt = promptBuilder.build(
                "Redis 缓存穿透如何解决",
                "Redis 缓存穿透如何解决",
                List.of(),
                List.of(),
                List.of(segment)
        );

        // Then
        assertThat(prompt)
                .startsWith("以下是相关的团队知识库内容")
                .contains("1、缓存穿透可以通过缓存空值和布隆过滤器解决。")
                .endsWith("用户问题：Redis 缓存穿透如何解决");
    }

    @Test
    @DisplayName("构建 RAG Prompt - 有短期记忆和重写问题时追加扩展上下文")
    void testBuild_WithMemoryAndRewrittenQuery_AppendExtraContext() {
        // Given
        RetrievedSegment segment = RetrievedSegment.builder()
                .text("Redis 缓存穿透可以缓存空值。")
                .build();

        // When
        String prompt = promptBuilder.build(
                "它怎么解决",
                "Redis 缓存穿透怎么解决",
                List.of(new ChatMessage("user", "Redis 缓存穿透是什么")),
                List.of(segment),
                null
        );

        // Then
        assertThat(prompt)
                .contains("短期会话记忆")
                .contains("检索用问题：Redis 缓存穿透怎么解决")
                .contains("用户问题：它怎么解决");
    }

    @Test
    @DisplayName("判断可用知识 - null 列表返回 false，有文本返回 true")
    void testHasUsableKnowledge_NullAndValidSegment_ReturnExpectedResult() {
        // Given
        RetrievedSegment segment = RetrievedSegment.builder().text("有效知识").build();

        // When & Then
        assertThat(promptBuilder.hasUsableKnowledge(null)).isFalse();
        assertThat(promptBuilder.hasUsableKnowledge(List.of(segment))).isTrue();
    }
}
