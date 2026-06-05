package com.team.rag.util;

import com.team.rag.bean.ChatMessage;
import com.team.rag.bean.RetrievedSegment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * RAG 提示词构建器。
 */
@Component
public class PromptBuilder {

    /**
     * 融合短期记忆、长期记忆、RAG 片段和用户问题，构造低幻觉提示词。
     */
    public String build(String originalQuery, String rewrittenQuery, List<ChatMessage> shortMemory, List<RetrievedSegment> longMemory, List<RetrievedSegment> ragSegments) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是团队智能知识库助手，请严格基于给定资料回答。\n");
        prompt.append("如果资料中没有答案，请明确回答：知识库中未找到相关答案。不要编造。\n\n");

        appendMessages(prompt, "短期会话记忆", shortMemory);
        appendSegments(prompt, "长期记忆", longMemory);
        appendSegments(prompt, "RAG 检索资料", ragSegments);

        if (StringUtils.hasText(rewrittenQuery) && !rewrittenQuery.equals(originalQuery)) {
            prompt.append("\n查询重写：").append(rewrittenQuery).append("\n");
        }
        prompt.append("\n用户原问题：").append(originalQuery).append("\n");
        prompt.append("请给出结构清晰、可执行的回答。");
        return prompt.toString();
    }

    /**
     * 知识库无命中时构造通用大模型兜底 Prompt。
     */
    public String buildGeneralFallback(String originalQuery, String rewrittenQuery, List<ChatMessage> shortMemory) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是通用 AI 助手。当前团队知识库没有检索到可用资料，请基于你的通用知识回答。\n");
        prompt.append("系统会在最终答案前标注知识库未命中，你不要重复这句标注。\n");
        prompt.append("如果问题涉及团队内部资料、私有配置、项目真实状态或必须依赖文档的内容，请明确建议用户先上传或补充相关资料。\n\n");

        appendMessages(prompt, "短期会话记忆", shortMemory);

        if (StringUtils.hasText(rewrittenQuery) && !rewrittenQuery.equals(originalQuery)) {
            prompt.append("\n查询重写：").append(rewrittenQuery).append("\n");
        }
        prompt.append("\n用户原问题：").append(originalQuery).append("\n");
        prompt.append("请给出结构清晰、可执行的回答。");
        return prompt.toString();
    }

    /**
     * 将短期对话记忆追加到提示词中。
     */
    private void appendMessages(StringBuilder prompt, String title, List<ChatMessage> messages) {
        prompt.append("## ").append(title).append("\n");
        if (messages == null || messages.isEmpty()) {
            prompt.append("无\n\n");
            return;
        }
        for (ChatMessage message : messages) {
            prompt.append("- ").append(message.getRole()).append(": ").append(message.getContent()).append("\n");
        }
        prompt.append("\n");
    }

    /**
     * 将检索命中的知识片段追加到提示词中，并保留来源元数据。
     */
    private void appendSegments(StringBuilder prompt, String title, List<RetrievedSegment> segments) {
        prompt.append("## ").append(title).append("\n");
        if (segments == null || segments.isEmpty()) {
            prompt.append("无\n\n");
            return;
        }
        for (int i = 0; i < segments.size(); i++) {
            RetrievedSegment segment = segments.get(i);
            prompt.append(i + 1).append(". ");
            if (segment.getMetadata() != null) {
                Object filename = segment.getMetadata().get("filename");
                Object docType = segment.getMetadata().get("docType");
                if (filename != null || docType != null) {
                    prompt.append("[").append(filename == null ? "unknown" : filename)
                            .append(" / ").append(docType == null ? "unknown" : docType).append("] ");
                }
            }
            prompt.append(segment.getText()).append("\n");
        }
        prompt.append("\n");
    }
}
