package com.team.rag.controller;

import com.team.rag.bean.RagQo;
import com.team.rag.service.RagQAService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * RAG 问答接口：短期记忆、查询重写、长期记忆、混合检索和流式大模型回答。
 */
@Tag(name = "RAG 问答", description = "团队 RAG 知识库混合检索问答接口")
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagQAController {

    private final RagQAService ragQAService;

    /**
     * RAG 混合检索问答，使用 text/event-stream 流式输出。
     */
    @Operation(summary = "RAG 混合检索问答")
    @PostMapping(value = "/qa", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=utf-8")
    public Flux<String> qa(@Valid @RequestBody RagQo qo) {
        return ragQAService.qa(qo);
    }
}
