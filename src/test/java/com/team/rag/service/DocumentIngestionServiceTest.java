package com.team.rag.service;

import com.team.rag.bean.DocumentSegment;
import com.team.rag.bean.DocumentStatus;
import com.team.rag.bean.DocumentVersion;
import com.team.rag.bean.IngestionJob;
import com.team.rag.bean.IngestionStatus;
import com.team.rag.bean.RagDocument;
import com.team.rag.bean.SparseVector;
import com.team.rag.client.DashScopeClient;
import com.team.rag.client.PineconeClient;
import com.team.rag.config.RagProperties;
import com.team.rag.repository.DocumentLifecycleRepository;
import com.team.rag.repository.InMemoryDocumentLifecycleRepository;
import com.team.rag.util.BM25SparseEncoder;
import com.team.rag.util.SemanticSplitter;
import org.apache.tika.Tika;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("文档入库任务服务单元测试")
@ExtendWith(MockitoExtension.class)
class DocumentIngestionServiceTest {

    @Mock
    private Tika tika;
    @Mock
    private SemanticSplitter semanticSplitter;
    @Mock
    private DashScopeClient dashScopeClient;
    @Mock
    private PineconeClient pineconeClient;
    @Mock
    private BM25SparseEncoder sparseEncoder;

    private DocumentLifecycleRepository repository;
    private RagProperties properties;

    @BeforeEach
    void setUp() {
        repository = new InMemoryDocumentLifecycleRepository();
        properties = new RagProperties();
    }

    @Test
    @DisplayName("执行入库任务 - 成功写入向量并激活版本")
    @SuppressWarnings("unchecked")
    void testIngest_Success_ActivateVersion() throws Exception {
        // Given
        Path file = Files.createTempFile("rag-doc", ".txt");
        Files.writeString(file, "Redis 缓存穿透解决方案");
        prepareDocument("job-1", file);
        DocumentIngestionService service = newService();
        when(tika.parseToString(any(InputStream.class))).thenReturn("Redis 缓存穿透解决方案");
        DocumentSegment segment = DocumentSegment.builder()
                .id("doc:doc-1:1:0")
                .documentId("doc-1")
                .index(0)
                .text("Redis 缓存穿透解决方案")
                .metadata(Map.of("text", "Redis 缓存穿透解决方案", "status", "ACTIVE"))
                .build();
        when(semanticSplitter.split(eq("doc-1"), eq("Redis 缓存穿透解决方案"), anyMap()))
                .thenReturn(List.of(segment));
        when(dashScopeClient.embed(List.of("Redis 缓存穿透解决方案")))
                .thenReturn(List.of(new float[]{0.1f, 0.2f}));
        when(sparseEncoder.encode("Redis 缓存穿透解决方案"))
                .thenReturn(new SparseVector(List.of(1), List.of(1.0)));
        ArgumentCaptor<List<PineconeClient.VectorRecord>> recordsCaptor = ArgumentCaptor.forClass(List.class);

        // When
        service.ingest("job-1");

        // Then
        verify(pineconeClient).upsert(eq("team-rag-documents"), recordsCaptor.capture());
        assertThat(recordsCaptor.getValue()).hasSize(1);
        assertThat(repository.findVersion("doc-1", 1).orElseThrow().getStatus()).isEqualTo(DocumentStatus.ACTIVE);
        assertThat(repository.findJob("job-1").orElseThrow().getStatus()).isEqualTo(IngestionStatus.SUCCESS);
        assertThat(repository.findChunks("doc-1", 1)).hasSize(1);
    }

    @Test
    @DisplayName("执行入库任务 - 解析为空时标记失败")
    void testIngest_BlankParsedText_MarkFailed() throws Exception {
        // Given
        Path file = Files.createTempFile("rag-doc", ".txt");
        Files.writeString(file, "Redis");
        prepareDocument("job-1", file);
        DocumentIngestionService service = newService();
        when(tika.parseToString(any(InputStream.class))).thenReturn(" ");

        // When
        service.ingest("job-1");

        // Then
        assertThat(repository.findVersion("doc-1", 1).orElseThrow().getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(repository.findJob("job-1").orElseThrow().getStatus()).isEqualTo(IngestionStatus.FAILED);
        assertThat(repository.findJob("job-1").orElseThrow().getErrorMessage()).contains("文档未解析出有效文本");
    }

    @Test
    @DisplayName("执行入库任务 - 语义分块为空时标记失败")
    void testIngest_EmptySegments_MarkFailed() throws Exception {
        // Given
        Path file = Files.createTempFile("rag-doc", ".txt");
        Files.writeString(file, "Redis");
        prepareDocument("job-1", file);
        DocumentIngestionService service = newService();
        when(tika.parseToString(any(InputStream.class))).thenReturn("Redis");
        when(semanticSplitter.split(eq("doc-1"), eq("Redis"), anyMap())).thenReturn(List.of());

        // When
        service.ingest("job-1");

        // Then
        assertThat(repository.findVersion("doc-1", 1).orElseThrow().getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(repository.findJob("job-1").orElseThrow().getStatus()).isEqualTo(IngestionStatus.FAILED);
    }

    @Test
    @DisplayName("执行入库任务 - 新版本成功后清理旧版本向量")
    @SuppressWarnings("unchecked")
    void testIngest_NewVersion_DeleteSupersededVectors() throws Exception {
        // Given
        Path file = Files.createTempFile("rag-doc-v2", ".txt");
        Files.writeString(file, "Redis cache update guide");
        OffsetDateTime now = OffsetDateTime.now();
        repository.saveDocument(RagDocument.builder()
                .id("doc-1")
                .filename("redis.txt")
                .docType("研发规范")
                .project("infra")
                .currentVersion(1)
                .status(DocumentStatus.ACTIVE)
                .createdAt(now.minusDays(1))
                .updatedAt(now)
                .build());
        repository.saveVersion(DocumentVersion.builder()
                .id("doc-1:0")
                .documentId("doc-1")
                .version(0)
                .fileHash("hash-0")
                .status(DocumentStatus.ACTIVE)
                .segmentCount(0)
                .createdAt(now.minusDays(2))
                .activatedAt(now.minusDays(2))
                .build());
        repository.saveVersion(DocumentVersion.builder()
                .id("doc-1:1")
                .documentId("doc-1")
                .version(1)
                .fileHash("hash-1")
                .status(DocumentStatus.ACTIVE)
                .segmentCount(1)
                .createdAt(now.minusDays(1))
                .activatedAt(now.minusDays(1))
                .build());
        repository.replaceChunks("doc-1", 1, List.of(com.team.rag.bean.StoredDocumentChunk.builder()
                .id("old-chunk")
                .documentId("doc-1")
                .version(1)
                .chunkIndex(0)
                .vectorId("old-vector")
                .text("old")
                .metadata(Map.of())
                .createdAt(now.minusDays(1))
                .build()));
        repository.saveVersion(DocumentVersion.builder()
                .id("doc-1:2")
                .documentId("doc-1")
                .version(2)
                .filePath(file.toString())
                .fileHash("hash-2")
                .status(DocumentStatus.PROCESSING)
                .segmentCount(0)
                .createdAt(now)
                .build());
        repository.saveJob(IngestionJob.builder()
                .id("job-2")
                .documentId("doc-1")
                .version(2)
                .status(IngestionStatus.PENDING)
                .progress(0)
                .retryCount(0)
                .createdAt(now)
                .updatedAt(now)
                .build());
        DocumentIngestionService service = newService();
        when(tika.parseToString(any(InputStream.class))).thenReturn("Redis cache update guide");
        DocumentSegment segment = DocumentSegment.builder()
                .id("doc:doc-1:2:0")
                .documentId("doc-1")
                .index(0)
                .text("Redis cache update guide")
                .metadata(Map.of("text", "Redis cache update guide", "status", "ACTIVE"))
                .build();
        when(semanticSplitter.split(eq("doc-1"), eq("Redis cache update guide"), anyMap()))
                .thenReturn(List.of(segment));
        when(dashScopeClient.embed(List.of("Redis cache update guide")))
                .thenReturn(List.of(new float[]{0.3f, 0.4f}));
        when(sparseEncoder.encode("Redis cache update guide"))
                .thenReturn(new SparseVector(List.of(2), List.of(1.0)));

        // When
        service.ingest("job-2");

        // Then
        verify(pineconeClient).deleteByIds(eq("team-rag-documents"), eq(List.of("old-vector")));
        assertThat(repository.findVersion("doc-1", 1).orElseThrow().getStatus()).isEqualTo(DocumentStatus.SUPERSEDED);
        assertThat(repository.findVersion("doc-1", 2).orElseThrow().getStatus()).isEqualTo(DocumentStatus.ACTIVE);
    }

    @Test
    @DisplayName("执行入库任务 - 任务记录缺失时标记失败")
    void testIngest_MissingJob_MarkFailed() {
        // Given
        DocumentLifecycleRepository mockRepository = mock(DocumentLifecycleRepository.class);
        DocumentIngestionService service = new DocumentIngestionService(tika, semanticSplitter, dashScopeClient,
                pineconeClient, sparseEncoder, properties, mockRepository);
        when(mockRepository.findJob("missing-job")).thenReturn(Optional.empty());
        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);

        // When
        service.ingest("missing-job");

        // Then
        verify(mockRepository, never()).markIngestionFailed(eq("missing-job"), errorCaptor.capture());
    }

    @Test
    @DisplayName("执行入库任务 - 文档记录缺失时标记失败")
    void testIngest_MissingDocument_MarkFailed() {
        // Given
        DocumentLifecycleRepository mockRepository = mock(DocumentLifecycleRepository.class);
        DocumentIngestionService service = new DocumentIngestionService(tika, semanticSplitter, dashScopeClient,
                pineconeClient, sparseEncoder, properties, mockRepository);
        IngestionJob job = IngestionJob.builder()
                .id("job-1")
                .documentId("missing-doc")
                .version(1)
                .build();
        when(mockRepository.findJob("job-1")).thenReturn(Optional.of(job));
        when(mockRepository.findDocument("missing-doc")).thenReturn(Optional.empty());
        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);

        // When
        service.ingest("job-1");

        // Then
        verify(mockRepository).markIngestionFailed(eq("job-1"), errorCaptor.capture());
        assertThat(errorCaptor.getValue()).contains("missing-doc");
    }

    @Test
    @DisplayName("执行入库任务 - 文档版本缺失时标记失败")
    void testIngest_MissingVersion_MarkFailed() {
        // Given
        DocumentLifecycleRepository mockRepository = mock(DocumentLifecycleRepository.class);
        DocumentIngestionService service = new DocumentIngestionService(tika, semanticSplitter, dashScopeClient,
                pineconeClient, sparseEncoder, properties, mockRepository);
        IngestionJob job = IngestionJob.builder()
                .id("job-1")
                .documentId("doc-1")
                .version(9)
                .build();
        when(mockRepository.findJob("job-1")).thenReturn(Optional.of(job));
        when(mockRepository.findDocument("doc-1")).thenReturn(Optional.of(RagDocument.builder().id("doc-1").build()));
        when(mockRepository.findVersion("doc-1", 9)).thenReturn(Optional.empty());
        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);

        // When
        service.ingest("job-1");

        // Then
        verify(mockRepository).markIngestionFailed(eq("job-1"), errorCaptor.capture());
        assertThat(errorCaptor.getValue()).contains("doc-1#9");
    }

    private DocumentIngestionService newService() {
        return new DocumentIngestionService(tika, semanticSplitter, dashScopeClient, pineconeClient, sparseEncoder, properties, repository);
    }

    private void prepareDocument(String jobId, Path file) {
        OffsetDateTime now = OffsetDateTime.now();
        repository.saveDocument(RagDocument.builder()
                .id("doc-1")
                .filename("redis.txt")
                .docType("研发规范")
                .project("infra")
                .status(DocumentStatus.PROCESSING)
                .createdAt(now)
                .updatedAt(now)
                .build());
        repository.saveVersion(DocumentVersion.builder()
                .id("doc-1:1")
                .documentId("doc-1")
                .version(1)
                .filePath(file.toString())
                .fileHash("hash-1")
                .status(DocumentStatus.PROCESSING)
                .segmentCount(0)
                .createdAt(now)
                .build());
        repository.saveJob(IngestionJob.builder()
                .id(jobId)
                .documentId("doc-1")
                .version(1)
                .status(IngestionStatus.PENDING)
                .progress(0)
                .retryCount(0)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }
}
