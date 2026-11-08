package com.team.rag.util;

import com.team.rag.bean.RetrievedSegment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 构建 RAG 答案评测 Prompt，用于离线 LLM Judge 或人工复核。
 */
@Component
public class RagAnswerJudgePromptBuilder {

    /**
     * 把问题、答案和检索片段组织为评分输入，要求评测方按固定 JSON 返回结果。
     */
    public String build(String question, String answer, List<RetrievedSegment> supportingSegments) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是 RAG 答案质量评测员。请只依据给定的检索片段评估答案，不要引入外部知识。\n")
                .append("评分范围为 0 到 1，1 表示最好；hallucinationRisk 取 low、medium、high。\n")
                .append("请返回严格 JSON，字段包括 faithfulnessScore、answerRelevanceScore、citationCoverageScore、hallucinationRisk、unsupportedClaims、reason。\n\n")
                .append("用户问题：").append(text(question)).append("\n\n")
                .append("生成答案：").append(text(answer)).append("\n\n")
                .append("检索片段：\n");
        appendSegments(prompt, supportingSegments);
        prompt.append("\n评判标准：\n")
                .append("1. faithfulnessScore：答案中的事实是否能被检索片段支撑。\n")
                .append("2. answerRelevanceScore：答案是否直接回应用户问题。\n")
                .append("3. citationCoverageScore：关键结论是否能对应到至少一个检索片段。\n")
                .append("4. hallucinationRisk：如果答案包含检索片段没有支撑的项目事实、配置、数据或结论，风险应升高。\n")
                .append("5. 如果检索片段为空，只有答案明确说明社团资料知识库未命中且没有编造内部事实时，才可以给较低幻觉风险。");
        return prompt.toString();
    }

    private void appendSegments(StringBuilder prompt, List<RetrievedSegment> supportingSegments) {
        if (supportingSegments == null || supportingSegments.isEmpty()) {
            prompt.append("无\n");
            return;
        }
        int index = 1;
        for (RetrievedSegment segment : supportingSegments) {
            if (segment == null || !StringUtils.hasText(segment.getText())) {
                continue;
            }
            prompt.append(index++)
                    .append("、id=")
                    .append(text(segment.getId()))
                    .append("，score=")
                    .append(segment.getScore() == null ? "" : segment.getScore())
                    .append("，内容=")
                    .append(segment.getText().trim())
                    .append("\n");
        }
        if (index == 1) {
            prompt.append("无\n");
        }
    }

    private String text(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }
}
