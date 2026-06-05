package com.team.rag.service;

import com.team.rag.bean.ChatMessage;
import com.team.rag.client.DashScopeClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 查询重写服务。
 *
 * <p>把用户当前问题和短期记忆合并，改写成适合检索的独立查询。</p>
 */
@Service
@RequiredArgsConstructor
public class QueryRewriteService {

    private final DashScopeClient dashScopeClient;

    /**
     * 基于短期记忆改写查询。
     */
    public String rewrite(String query, List<ChatMessage> shortMemory) {
        if (!StringUtils.hasText(query) || shortMemory == null || shortMemory.isEmpty()) {
            return query;
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append("请将用户问题改写成适合团队知识库检索的独立查询。只输出改写后的查询，不要解释。\n");
        prompt.append("短期记忆：\n");
        shortMemory.forEach(message -> prompt.append(message.getRole()).append(": ").append(message.getContent()).append("\n"));
        prompt.append("用户问题：").append(query);
        try {
            String rewritten = dashScopeClient.complete(prompt.toString()).trim();
            return StringUtils.hasText(rewritten) ? rewritten : query;
        } catch (Exception ex) {
            return query;
        }
    }
}
