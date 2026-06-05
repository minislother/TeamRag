package com.team.rag.controller;

import com.team.rag.bean.DocumentForm;
import com.team.rag.service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文档管理接口：上传、删除等
 */
@Tag(name = "文档管理", description = "团队RAG知识库文档上传接口")
@RestController
@RequestMapping("/api/document")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @Operation(summary = "文档上传并入库向量库")
    @PostMapping("/upload")
    public String upload(DocumentForm form) {
        return documentService.uploadAndStore(form.getFile(), form.getDocType());
    }
}
