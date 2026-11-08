package com.team.rag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.bean.ChatMessage;
import com.team.rag.client.DashScopeClient;
import com.team.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 短期会话记忆服务。
 *
 * <p>优先使用 Redis 保存会话窗口；本地没有 Redis 时自动回退到进程内 Map，方便开发阶段直接跑通。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShortTermMemoryService {

    private static final TypeReference<List<ChatMessage>> MESSAGE_LIST = new TypeReference<>() {
    };

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final DashScopeClient dashScopeClient;
    private final RagProperties properties;
    private final Map<String, List<ChatMessage>> fallbackStore = new ConcurrentHashMap<>();
    private final Map<String, java.time.Instant> fallbackExpiry = new ConcurrentHashMap<>();

    /**
     * 获取当前会话短期记忆。
     */
    public List<ChatMessage> getRecentMessages(String conversationId) {
        java.time.Instant expires=fallbackExpiry.get(conversationId);
        if(expires!=null && !expires.isAfter(java.time.Instant.now())) {
            fallbackStore.remove(conversationId);fallbackExpiry.remove(conversationId);
        }
        try {
            String raw = redisTemplate.opsForValue().get(key(conversationId));
            if (raw == null || raw.isBlank()) {
                return new ArrayList<>(fallbackStore.getOrDefault(conversationId, List.of()));
            }
            return objectMapper.readValue(raw, MESSAGE_LIST);
        } catch (Exception ex) {
            log.debug("Redis 短期记忆读取失败，回退内存: {}", ex.getMessage());
            return new ArrayList<>(fallbackStore.getOrDefault(conversationId, List.of()));
        }
    }

    /**
     * 追加一轮对话，并按滑动窗口裁剪。
     */
    public void appendExchange(String conversationId, String userQuery, String assistantAnswer) {
        List<ChatMessage> messages = getRecentMessages(conversationId);
        messages.add(new ChatMessage("user", userQuery));
        messages.add(new ChatMessage("assistant", assistantAnswer));
        messages = compactIfNeeded(messages);
        persist(conversationId, messages);
    }

    /**
     * 当会话超过阈值时压缩早期消息，只保留摘要和最近窗口。
     */
    private List<ChatMessage> compactIfNeeded(List<ChatMessage> messages) {
        int windowSize = Math.max(properties.getMemory().getWindowSize(), 2);
        int threshold = Math.max(properties.getMemory().getSummaryThreshold(), windowSize);
        if (messages.size() <= threshold) {
            return new ArrayList<>(messages);
        }
        List<ChatMessage> older = messages.subList(0, Math.max(messages.size() - windowSize, 0));
        List<ChatMessage> recent = tail(messages, windowSize);
        StringBuilder prompt = new StringBuilder("请把以下社团资料知识库对话压缩成一句上下文摘要，保留用户目标、关键约束和已确认事实：\n");
        older.forEach(message -> prompt.append(message.getRole()).append(": ").append(message.getContent()).append("\n"));
        String summary;
        try {
            summary = dashScopeClient.complete(prompt.toString());
        } catch (Exception ex) {
            summary = "前文已有多轮与社团资料知识库相关的追问。";
        }
        List<ChatMessage> compacted = new ArrayList<>();
        compacted.add(new ChatMessage("summary", summary));
        compacted.addAll(recent);
        return compacted;
    }

    /**
     * 取消息列表末尾的固定窗口，作为短期记忆上下文。
     */
    private List<ChatMessage> tail(List<ChatMessage> messages, int size) {
        if (messages.size() <= size) {
            return new ArrayList<>(messages);
        }
        return new ArrayList<>(messages.subList(messages.size() - size, messages.size()));
    }

    /**
     * 同步写入内存兜底缓存和 Redis，Redis 不可用时保留本地会话能力。
     */
    private void persist(String conversationId, List<ChatMessage> messages) {
        fallbackStore.put(conversationId, new ArrayList<>(messages));
        fallbackExpiry.put(conversationId,java.time.Instant.now().plus(Duration.ofDays(7)));
        try {
            redisTemplate.opsForValue().set(key(conversationId), objectMapper.writeValueAsString(messages), Duration.ofDays(7));
        } catch (Exception ex) {
            log.debug("Redis 短期记忆写入失败，仅保留内存记忆: {}", ex.getMessage());
        }
    }

    /**
     * 生成 Redis 中保存会话窗口的键名。
     */
    private String key(String conversationId) {
        return "team-rag:memory:" + conversationId;
    }
}
