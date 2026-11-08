package com.team.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.bean.ChatMessage;
import com.team.rag.client.DashScopeClient;
import com.team.rag.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("短期记忆服务单元测试")
@ExtendWith(MockitoExtension.class)
class ShortTermMemoryServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private DashScopeClient dashScopeClient;

    private ObjectMapper objectMapper;
    private RagProperties properties;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        properties = new RagProperties();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("获取短期记忆 - Redis 有数据时反序列化返回")
    void testGetRecentMessages_RedisHit_ReturnMessages() throws Exception {
        // Given
        ShortTermMemoryService service = newService();
        String json = objectMapper.writeValueAsString(List.of(new ChatMessage("user", "Redis 是什么")));
        when(valueOperations.get("team-rag:memory:demo")).thenReturn(json);

        // When
        List<ChatMessage> result = service.getRecentMessages("demo");

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).isEqualTo("Redis 是什么");
    }

    @Test
    @DisplayName("追加短期记忆 - 写入 Redis 并保留本地兜底")
    void testAppendExchange_Success_PersistMessages() {
        // Given
        ShortTermMemoryService service = newService();
        when(valueOperations.get("team-rag:memory:demo")).thenReturn(null);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);

        // When
        service.appendExchange("demo", "Redis 缓存穿透如何解决", "缓存空值或使用布隆过滤器。");

        // Then
        verify(valueOperations).set(eq("team-rag:memory:demo"), jsonCaptor.capture(), any(Duration.class));
        assertThat(jsonCaptor.getValue())
                .contains("Redis 缓存穿透如何解决")
                .contains("缓存空值或使用布隆过滤器");
    }

    @Test
    @DisplayName("获取短期记忆 - Redis 读取异常时返回本地兜底")
    void testGetRecentMessages_RedisError_ReturnFallbackMessages() {
        // Given
        ShortTermMemoryService service = newService();
        when(valueOperations.get("team-rag:memory:demo")).thenThrow(new RuntimeException("redis down"));

        // When
        List<ChatMessage> result = service.getRecentMessages("demo");

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("追加短期记忆 - 未超过摘要阈值时保留完整上下文")
    void testAppendExchange_BelowSummaryThreshold_KeepFullContext() throws Exception {
        // Given
        properties.getMemory().setWindowSize(2);
        properties.getMemory().setSummaryThreshold(6);
        ShortTermMemoryService service = newService();
        String existing = objectMapper.writeValueAsString(List.of(
                new ChatMessage("user", "第一轮问题"),
                new ChatMessage("assistant", "第一轮回答"),
                new ChatMessage("user", "第二轮问题"),
                new ChatMessage("assistant", "第二轮回答")
        ));
        when(valueOperations.get("team-rag:memory:demo")).thenReturn(existing);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);

        // When
        service.appendExchange("demo", "第三轮问题", "第三轮回答");

        // Then
        verify(valueOperations).set(eq("team-rag:memory:demo"), jsonCaptor.capture(), any(Duration.class));
        assertThat(jsonCaptor.getValue())
                .contains("第一轮问题")
                .contains("第二轮问题")
                .contains("第三轮回答")
                .doesNotContain("summary");
        verifyNoInteractions(dashScopeClient);
    }

    @Test
    @DisplayName("追加短期记忆 - 超过阈值时生成摘要并保留最近窗口")
    void testAppendExchange_OverThreshold_CompactWithSummary() throws Exception {
        // Given
        properties.getMemory().setWindowSize(2);
        properties.getMemory().setSummaryThreshold(2);
        ShortTermMemoryService service = newService();
        String existing = objectMapper.writeValueAsString(List.of(
                new ChatMessage("user", "第一轮问题"),
                new ChatMessage("assistant", "第一轮回答")
        ));
        when(valueOperations.get("team-rag:memory:demo")).thenReturn(existing);
        when(dashScopeClient.complete(org.mockito.ArgumentMatchers.contains("第一轮问题"))).thenReturn("前文摘要");
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);

        // When
        service.appendExchange("demo", "第二轮问题", "第二轮回答");

        // Then
        verify(valueOperations).set(eq("team-rag:memory:demo"), jsonCaptor.capture(), any(Duration.class));
        assertThat(jsonCaptor.getValue())
                .contains("summary")
                .contains("前文摘要")
                .contains("第二轮回答");
    }

    private ShortTermMemoryService newService() {
        return new ShortTermMemoryService(redisTemplate, objectMapper, dashScopeClient, properties);
    }
}
