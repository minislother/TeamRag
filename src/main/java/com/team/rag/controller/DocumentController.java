package com.team.rag.controller;

import com.team.rag.bean.DocumentForm;
import com.team.rag.bean.DocumentUploadResponse;
import com.team.rag.service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文档管理接口：上传团队项目资料、研发规范、新人培训材料等。
 */
@Tag(name = "文档管理", description = "团队 RAG 知识库文档上传接口")
@RestController
@RequestMapping("/api/document")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    /**
     * 文档上传并入库 Pinecone。
     */
    @Operation(summary = "文档上传并入库向量库")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentUploadResponse upload(@ModelAttribute DocumentForm form) {
        return documentService.uploadAndStore(form);
    }
}
