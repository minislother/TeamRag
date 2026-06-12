package com.team.rag.util;

import com.team.rag.bean.ChatMessage;
import com.team.rag.bean.RetrievedSegment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 提示词构建器。
 */
@Component
public class PromptBuilder {

    /**
     * 融合短期记忆、长期记忆、RAG 片段和用户问题，构造增强后的提示词。
     */
    public String build(String originalQuery, String rewrittenQuery, List<ChatMessage> shortMemory, List<RetrievedSegment> longMemory, List<RetrievedSegment> ragSegments) {
        StringBuilder context = new StringBuilder();
        context.append("以下是相关的团队知识库内容，请基于这些内容回答问题，若内容中无答案，请说明“知识库中未找到相关答案”，不要编造：\n");

        // 检索片段按 1、2、3 的格式加入 Prompt，长期记忆也按同样格式合并。
        List<RetrievedSegment> knowledge = new ArrayList<>();
        addUsableSegments(knowledge, longMemory);
        addUsableSegments(knowledge, ragSegments);
        for (int i = 0; i < knowledge.size(); i++) {
            context.append(String.format("%d、%s\n", i + 1, knowledge.get(i).getText()));
        }

        // 短期记忆和查询重写只有存在内容时才加入，避免影响普通 RAG Prompt。
        appendMessagesIfPresent(context, shortMemory);
        if (StringUtils.hasText(rewrittenQuery) && !rewrittenQuery.equals(originalQuery)) {
            context.append("\n检索用问题：").append(rewrittenQuery).append("\n");
        }
        context.append("\n用户问题：").append(originalQuery);
        return context.toString();
    }

    /**
     * 知识库无命中时，转为通用大模型回答。
     */
    public String buildGeneral(String originalQuery, String rewrittenQuery, List<ChatMessage> shortMemory) {
        return "请回答问题：" + originalQuery;
    }

    /**
     * 判断检索结果中是否存在可用于增强 Prompt 的文本片段。
     */
    public boolean hasUsableKnowledge(List<RetrievedSegment> ragSegments) {
        return ragSegments != null && ragSegments.stream()
                .filter(segment -> segment != null)
                .map(RetrievedSegment::getText)
                .anyMatch(StringUtils::hasText);
    }

    private void addUsableSegments(List<RetrievedSegment> target, List<RetrievedSegment> segments) {
        if (segments == null) {
            return;
        }
        segments.stream()
                .filter(segment -> segment != null && StringUtils.hasText(segment.getText()))
                .forEach(target::add);
    }

    /**
     * 将短期对话记忆追加到提示词中，帮助大模型理解连续追问。
     */
    private void appendMessagesIfPresent(StringBuilder prompt, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        prompt.append("\n短期会话记忆：\n");
        for (ChatMessage message : messages) {
            prompt.append("- ").append(message.getRole()).append(": ").append(message.getContent()).append("\n");
        }
    }
}
