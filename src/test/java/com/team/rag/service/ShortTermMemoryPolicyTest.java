package com.team.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.bean.ChatMessage;
import com.team.rag.client.DashScopeClient;
import com.team.rag.config.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 用可控 Redis/模型替身验证真实服务的默认压缩周期，不用于声称模型效果或性能。 */
@DisplayName("短期记忆策略实证测试")
class ShortTermMemoryPolicyTest {
    @Test
    @DisplayName("默认窗口 - 第七轮和第九轮压缩且保留最近四轮")
    void testAppendExchange_DefaultWindow_CompressAtSevenAndNine() {
        // Given - 保存 Redis JSON 的替身让每次读取都经过真实序列化路径。
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String,String> values = mock(ValueOperations.class);
        DashScopeClient model = mock(DashScopeClient.class);
        Map<String,String> store = new HashMap<>();
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> store.get(call.getArgument(0)));
        doAnswer(call -> { store.put(call.getArgument(0),call.getArgument(1)); return null; })
                .when(values).set(anyString(),anyString(),eq(Duration.ofDays(7)));
        when(model.complete(anyString())).thenReturn("已确认的历史约束");
        ShortTermMemoryService service = new ShortTermMemoryService(redis,new ObjectMapper(),model,new RagProperties());
        List<Integer> sizes = new ArrayList<>();
        // When
        for (int turn=1;turn<=9;turn++) {
            service.appendExchange("policy", "问题"+turn,"回答"+turn);
            sizes.add(service.getRecentMessages("policy").size());
        }
        // Then
        assertThat(sizes).containsExactly(2,4,6,8,10,12,9,11,9);
        assertThat(service.getRecentMessages("policy")).extracting(ChatMessage::getContent)
                .containsExactly("已确认的历史约束","问题6","回答6","问题7","回答7","问题8","回答8","问题9","回答9");
        verify(model,times(2)).complete(anyString());
        verify(values,times(9)).set(anyString(),anyString(),eq(Duration.ofDays(7)));
        System.out.println("MEMORY_DEFAULT_SIZES="+sizes+"; summaries=2; writeTTL=7d");
    }

    @Test
    @DisplayName("摘要服务故障 - 通用兜底会丢失窗口外原始事实")
    void testAppendExchange_SummaryFailure_LosesOldFact() {
        // Given - Redis 返回空值时，真实服务读取自身的本地兜底。
        StringRedisTemplate redis=mock(StringRedisTemplate.class);
        ValueOperations<String,String> values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        DashScopeClient model=mock(DashScopeClient.class);
        when(model.complete(anyString())).thenThrow(new IllegalStateException("model unavailable"));
        ShortTermMemoryService service=new ShortTermMemoryService(redis,new ObjectMapper(),model,new RagProperties());
        // When
        service.appendExchange("failure","预算上限为500元","已确认");
        for(int turn=2;turn<=7;turn++) service.appendExchange("failure","问题"+turn,"回答"+turn);
        // Then
        assertThat(service.getRecentMessages("failure")).hasSize(9)
                .extracting(ChatMessage::getContent).doesNotContain("预算上限为500元")
                .contains("前文已有多轮与社团资料知识库相关的追问。");
    }

    @Test
    @DisplayName("Redis过期模拟 - 当前进程仍可从无TTL本地兜底恢复旧记录")
    void testGetRecentMessages_RedisMiss_LocalFallbackRetainsHistory() {
        // Given
        StringRedisTemplate redis=mock(StringRedisTemplate.class);
        ValueOperations<String,String> values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ShortTermMemoryService service=new ShortTermMemoryService(redis,new ObjectMapper(),mock(DashScopeClient.class),new RagProperties());
        service.appendExchange("expired","旧问题","旧回答");
        // When - 缺失 Redis key 不等价于整个系统的会话过期。
        List<ChatMessage> result=service.getRecentMessages("expired");
        // Then
        assertThat(result).extracting(ChatMessage::getContent).containsExactly("旧问题","旧回答");
    }
}
