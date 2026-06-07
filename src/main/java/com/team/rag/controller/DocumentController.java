package com.team.rag.controller;

import com.team.rag.bean.DocumentForm;
import com.team.rag.bean.DocumentStatusResponse;
import com.team.rag.bean.DocumentUploadResponse;
import com.team.rag.bean.IngestionJobResponse;
import com.team.rag.service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    /**
     * 查询文档列表和当前入库状态。
     */
    @Operation(summary = "查询文档状态列表")
    @GetMapping
    public List<DocumentStatusResponse> list() {
        return documentService.listDocuments();
    }

    /**
     * 查询单个文档的版本状态。
     */
    @Operation(summary = "查询单个文档状态")
    @GetMapping("/{documentId}")
    public DocumentStatusResponse detail(@PathVariable String documentId) {
        return documentService.getDocument(documentId);
    }

    /**
     * 查询单个入库任务状态。
     */
    @Operation(summary = "查询文档入库任务状态")
    @GetMapping("/jobs/{jobId}")
    public IngestionJobResponse job(@PathVariable String jobId) {
        return documentService.getJob(jobId);
    }
}
