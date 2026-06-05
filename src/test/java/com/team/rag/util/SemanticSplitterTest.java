package com.team.rag.util;

import com.team.rag.bean.DocumentSegment;
import com.team.rag.client.DashScopeClient;
import com.team.rag.config.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("语义分块工具单元测试")
@ExtendWith(MockitoExtension.class)
class SemanticSplitterTest {

    @Mock
    private DashScopeClient dashScopeClient;

    @Test
    @DisplayName("语义分块 - 空文本返回空列表")
    void testSplit_BlankText_ReturnEmptyList() {
        // Given
        SemanticSplitter splitter = new SemanticSplitter(dashScopeClient, new RagProperties());

        // When
        List<DocumentSegment> result = splitter.split("doc-1", " ", Map.of());

        // Then
        assertThat(result).isEmpty();
        verifyNoInteractions(dashScopeClient);
    }

    @Test
    @DisplayName("语义分块 - 单个基础片段直接包装为文档片段")
    void testSplit_SingleSegment_ReturnDocumentSegment() {
        // Given
        SemanticSplitter splitter = new SemanticSplitter(dashScopeClient, new RagProperties());

        // When
        List<DocumentSegment> result = splitter.split("doc-1", "Redis 缓存穿透可以缓存空值。", Map.of("filename", "redis.md"));

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getText()).contains("Redis 缓存穿透");
        assertThat(result.get(0).getMetadata()).containsEntry("filename", "redis.md");
        verifyNoInteractions(dashScopeClient);
    }

    @Test
    @DisplayName("语义分块 - 多个基础片段使用 embedding 相似度决定是否合并")
    void testSplit_MultipleSegments_UseEmbeddingSimilarity() {
        // Given
        RagProperties properties = new RagProperties();
        properties.getSemanticSplitter().setSimilarityThreshold(0.95);
        SemanticSplitter splitter = new SemanticSplitter(dashScopeClient, properties);
        String longText = "A".repeat(350) + "。" + "B".repeat(350) + "。" + "C".repeat(350) + "。";
        when(dashScopeClient.embed(anyList())).thenAnswer(invocation -> {
            List<String> segments = invocation.getArgument(0);
            List<float[]> vectors = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                vectors.add(i % 2 == 0 ? new float[]{1f, 0f} : new float[]{0f, 1f});
            }
            return vectors;
        });

        // When
        List<DocumentSegment> result = splitter.split("doc-1", longText, Map.of());

        // Then
        assertThat(result).hasSizeGreaterThan(1);
        assertThat(result).allSatisfy(segment -> assertThat(segment.getDocumentId()).isEqualTo("doc-1"));
    }

    @Test
    @DisplayName("语义分块 - 无自然断句的超长文本会硬切块")
    void testSplit_LongTextWithoutPunctuation_SplitByMaxChars() {
        // Given
        RagProperties properties = new RagProperties();
        properties.getSemanticSplitter().setOverlapChars(0);
        SemanticSplitter splitter = new SemanticSplitter(dashScopeClient, properties);
        String longText = "A".repeat(750);
        when(dashScopeClient.embed(anyList())).thenAnswer(invocation -> {
            List<String> segments = invocation.getArgument(0);
            List<float[]> vectors = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                vectors.add(new float[]{0f, 0f});
            }
            return vectors;
        });

        // When
        List<DocumentSegment> result = splitter.split("doc-1", longText, Map.of());

        // Then
        assertThat(result).hasSize(2);
        assertThat(result).extracting(segment -> segment.getText().length())
                .containsExactly(600, 150);
    }

    @Test
    @DisplayName("语义分块 - 相邻片段相似度高时合并")
    void testSplit_HighSimilarity_MergeSegments() {
        // Given
        RagProperties properties = new RagProperties();
        properties.getSemanticSplitter().setSimilarityThreshold(0.5);
        SemanticSplitter splitter = new SemanticSplitter(dashScopeClient, properties);
        String longText = "A".repeat(350) + "。" + "B".repeat(350) + "。";
        when(dashScopeClient.embed(anyList())).thenAnswer(invocation -> {
            List<String> segments = invocation.getArgument(0);
            List<float[]> vectors = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                vectors.add(new float[]{1f, 0f});
            }
            return vectors;
        });

        // When
        List<DocumentSegment> result = splitter.split("doc-1", longText, Map.of());

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getText()).contains("A").contains("B");
    }
}
