package com.team.rag.service;

import com.team.rag.bean.ChatMessage;
import com.team.rag.client.DashScopeClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("查询重写服务单元测试")
@ExtendWith(MockitoExtension.class)
class QueryRewriteServiceTest {

    @Mock
    private DashScopeClient dashScopeClient;

    @Test
    @DisplayName("查询重写 - 无短期记忆时返回原问题")
    void testRewrite_NoShortMemory_ReturnOriginalQuery() {
        // Given
        QueryRewriteService service = new QueryRewriteService(dashScopeClient);

        // When
        String result = service.rewrite("Redis 缓存穿透如何解决", List.of());

        // Then
        assertThat(result).isEqualTo("Redis 缓存穿透如何解决");
        verifyNoInteractions(dashScopeClient);
    }

    @Test
    @DisplayName("查询重写 - 多轮追问补全技术对象和检索意图")
    void testRewrite_FollowUpQuestion_ReturnStandaloneRetrievalQuery() {
        // Given
        QueryRewriteService service = new QueryRewriteService(dashScopeClient);
        List<ChatMessage> memory = List.of(
                new ChatMessage("user", "Redis 缓存穿透是什么"),
                new ChatMessage("assistant", "可以通过缓存空值、布隆过滤器和参数校验处理")
        );
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(dashScopeClient.complete(promptCaptor.capture()))
                .thenReturn("Redis 缓存穿透在 Spring Boot 项目中如何处理");

        // When
        String result = service.rewrite("那在项目里怎么处理", memory);

        // Then
        assertThat(result).isEqualTo("Redis 缓存穿透在 Spring Boot 项目中如何处理");
        assertThat(promptCaptor.getValue())
                .contains("baituan 社团资料知识库")
                .contains("只输出改写后的查询，不要解释")
                .contains("user: Redis 缓存穿透是什么")
                .contains("assistant: 可以通过缓存空值、布隆过滤器和参数校验处理")
                .contains("用户问题：那在项目里怎么处理");
        verify(dashScopeClient).complete(promptCaptor.getValue());
    }

    @Test
    @DisplayName("查询重写 - 有短期记忆时使用模型改写")
    void testRewrite_WithShortMemory_ReturnRewrittenQuery() {
        // Given
        QueryRewriteService service = new QueryRewriteService(dashScopeClient);
        List<ChatMessage> memory = List.of(new ChatMessage("user", "刚才说 Redis"));
        when(dashScopeClient.complete(org.mockito.ArgumentMatchers.contains("短期记忆")))
                .thenReturn("Redis 缓存穿透解决方案");

        // When
        String result = service.rewrite("缓存穿透怎么处理", memory);

        // Then
        assertThat(result).isEqualTo("Redis 缓存穿透解决方案");
    }

    @Test
    @DisplayName("查询重写 - 模型异常时回退原问题")
    void testRewrite_ModelError_ReturnOriginalQuery() {
        // Given
        QueryRewriteService service = new QueryRewriteService(dashScopeClient);
        List<ChatMessage> memory = List.of(new ChatMessage("user", "刚才说 Redis"));
        when(dashScopeClient.complete(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new RuntimeException("model error"));

        // When
        String result = service.rewrite("缓存穿透怎么处理", memory);

        // Then
        assertThat(result).isEqualTo("缓存穿透怎么处理");
    }

    @Test
    @DisplayName("查询重写 - 用户问题为空时直接返回")
    void testRewrite_BlankQuery_ReturnBlankQuery() {
        // Given
        QueryRewriteService service = new QueryRewriteService(dashScopeClient);

        // When
        String result = service.rewrite(" ", List.of(new ChatMessage("user", "Redis")));

        // Then
        assertThat(result).isEqualTo(" ");
        verifyNoInteractions(dashScopeClient);
    }

    @Test
    @DisplayName("查询重写 - 模型返回空文本时回退原问题")
    void testRewrite_ModelReturnsBlank_ReturnOriginalQuery() {
        // Given
        QueryRewriteService service = new QueryRewriteService(dashScopeClient);
        List<ChatMessage> memory = List.of(new ChatMessage("user", "刚才说 Redis"));
        when(dashScopeClient.complete(org.mockito.ArgumentMatchers.anyString())).thenReturn(" ");

        // When
        String result = service.rewrite("缓存穿透怎么处理", memory);

        // Then
        assertThat(result).isEqualTo("缓存穿透怎么处理");
    }
}
