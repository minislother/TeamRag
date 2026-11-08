package com.team.rag.util;

import com.team.rag.bean.DocumentSegment;
import com.team.rag.client.DashScopeClient;
import com.team.rag.config.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** 固定向量只验证切分控制流及边界，不代替真实 embedding 的检索质量评估。 */
@DisplayName("语义切块策略实证测试")
class SemanticSplitterPolicyTest {
    @Test
    @DisplayName("相邻相似度 - 连续局部相似可合并首尾不相似的片段")
    void testSplit_AdjacentSimilarity_AllowsSemanticDrift() {
        // Given - 两个相邻夹角为35度，首尾70度；相邻cos约0.819超过0.78。
        DashScopeClient model=mock(DashScopeClient.class);
        when(model.embed(anyList())).thenReturn(List.of(new float[]{1,0},
                new float[]{0.819152f,0.573576f},new float[]{0.342020f,0.939693f}));
        SemanticSplitter splitter=new SemanticSplitter(model,new RagProperties());
        String text="甲".repeat(349)+"。"+"乙".repeat(349)+"。"+"丙".repeat(349)+"。";
        // When
        List<DocumentSegment> result=splitter.split("drift",text,Map.of());
        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getText()).hasSize(1052);
        System.out.println("ADJACENT_SIMILARITY: 3 base segments => 1 chunk, length=1052");
    }

    @Test
    @DisplayName("默认长度上限 - 高相似片段也会因换行预算拆开并重叠120字符")
    void testSplit_LengthBoundary_UsesOverlap() {
        // Given
        DashScopeClient model=mock(DashScopeClient.class);
        when(model.embed(anyList())).thenReturn(List.of(new float[]{1,0},new float[]{1,0},new float[]{1,0}));
        String text="A".repeat(599)+"。"+"B".repeat(599)+"。"+"C".repeat(598)+"。";
        // When
        List<DocumentSegment> result=new SemanticSplitter(model,new RagProperties()).split("boundary",text,Map.of());
        // Then
        assertThat(result).extracting(s->s.getText().length()).containsExactly(1201,720);
        assertThat(result.get(1).getText()).startsWith(result.get(0).getText().substring(1081)+"\n");
        System.out.println("DEFAULT_CHUNK_BOUNDARY: base=600,600,599; final=1201,720; overlap=120");
    }
}
