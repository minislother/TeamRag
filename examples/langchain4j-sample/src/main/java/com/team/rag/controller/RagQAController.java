package com.team.rag.controller;

import com.team.rag.bean.RagQo;
import com.team.rag.service.RagQAService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * RAG问答接口：混合检索+大模型问答
 */
@Tag(name = "RAG问答", description = "团队RAG知识库混合检索问答接口（流式输出）")
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagQAController {

    private final RagQAService ragQAService;

    @Operation(summary = "RAG混合检索问答（流式输出）")
    @PostMapping(value = "/qa", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=utf-8")
    public Flux<String> qa(@RequestBody RagQo qo) {
        // 若未传对话ID，默认设为1
        Long memoryId = qo.getMemoryId() == null ? 1L : qo.getMemoryId();
        return ragQAService.qa(qo.getQuery(), memoryId);
    }
}
