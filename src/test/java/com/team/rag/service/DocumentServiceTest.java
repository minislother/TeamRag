package com.team.rag.service;

import com.team.rag.bean.DocumentForm;
import com.team.rag.bean.DocumentSegment;
import com.team.rag.bean.DocumentUploadResponse;
import com.team.rag.bean.SparseVector;
import com.team.rag.client.DashScopeClient;
import com.team.rag.client.PineconeClient;
import com.team.rag.config.RagProperties;
import com.team.rag.util.BM25SparseEncoder;
import com.team.rag.util.SemanticSplitter;
import org.apache.tika.Tika;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("文档服务单元测试")
@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

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

    @Test
    @DisplayName("文档上传 - 空文件抛出异常")
    void testUploadAndStore_EmptyFile_ThrowException() {
        // Given
        DocumentService service = newService();
        DocumentForm form = new DocumentForm();
        form.setFile(new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]));

        // When & Then
        assertThatThrownBy(() -> service.uploadAndStore(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文档文件不能为空");
    }

    @Test
    @DisplayName("文档上传 - 文件为 null 抛出异常")
    void testUploadAndStore_NullFile_ThrowException() {
        // Given
        DocumentService service = newService();
        DocumentForm form = new DocumentForm();

        // When & Then
        assertThatThrownBy(() -> service.uploadAndStore(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文档文件不能为空");
    }

    @Test
    @DisplayName("文档上传 - 文件超过大小限制抛出异常")
    void testUploadAndStore_FileTooLarge_ThrowException() {
        // Given
        RagProperties properties = new RagProperties();
        properties.getDocument().setMaxUploadSizeMb(0);
        DocumentService service = new DocumentService(tika, semanticSplitter, dashScopeClient, pineconeClient, sparseEncoder, properties);
        DocumentForm form = new DocumentForm();
        form.setFile(new MockMultipartFile("file", "redis.txt", "text/plain", "Redis".getBytes()));

        // When & Then
        assertThatThrownBy(() -> service.uploadAndStore(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文档超过最大上传限制");
    }

    @Test
    @DisplayName("文档上传 - 解析不到文本抛出异常")
    void testUploadAndStore_ParsedTextBlank_ThrowException() throws Exception {
        // Given
        DocumentService service = newService();
        DocumentForm form = new DocumentForm();
        form.setFile(new MockMultipartFile("file", "redis.txt", "text/plain", "Redis".getBytes()));
        when(tika.parseToString(any(InputStream.class))).thenReturn(" ");

        // When & Then
        assertThatThrownBy(() -> service.uploadAndStore(form))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("文档未解析出有效文本");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("文档上传 - 成功解析、分块、向量化并入库")
    void testUploadAndStore_Success_StoreVectors() throws Exception {
        // Given
        DocumentService service = newService();
        DocumentForm form = new DocumentForm();
        form.setFile(new MockMultipartFile("file", "redis.txt", "text/plain", "Redis 文档".getBytes()));
        form.setDocType("研发规范");
        form.setProject("infra");
        form.setTags("Redis,缓存");
        when(tika.parseToString(any(InputStream.class))).thenReturn("Redis 缓存穿透解决方案");
        DocumentSegment segment = DocumentSegment.builder()
                .id("segment-1")
                .documentId("doc-1")
                .index(0)
                .text("Redis 缓存穿透解决方案")
                .metadata(Map.of("text", "Redis 缓存穿透解决方案"))
                .build();
        when(semanticSplitter.split(anyString(), eq("Redis 缓存穿透解决方案"), anyMap()))
                .thenReturn(List.of(segment));
        when(dashScopeClient.embed(List.of("Redis 缓存穿透解决方案")))
                .thenReturn(List.of(new float[]{0.1f, 0.2f}));
        when(sparseEncoder.encode("Redis 缓存穿透解决方案"))
                .thenReturn(new SparseVector(List.of(1), List.of(1.0)));
        ArgumentCaptor<List<PineconeClient.VectorRecord>> recordsCaptor = ArgumentCaptor.forClass(List.class);

        // When
        DocumentUploadResponse response = service.uploadAndStore(form);

        // Then
        assertThat(response.getFilename()).isEqualTo("redis.txt");
        assertThat(response.getDocType()).isEqualTo("研发规范");
        assertThat(response.getProject()).isEqualTo("infra");
        assertThat(response.getSegmentCount()).isEqualTo(1);
        verify(pineconeClient).upsert(eq("team-rag-documents"), recordsCaptor.capture());
        assertThat(recordsCaptor.getValue()).hasSize(1);
        assertThat(recordsCaptor.getValue().get(0).id()).isEqualTo("segment-1");
    }

    private DocumentService newService() {
        return new DocumentService(tika, semanticSplitter, dashScopeClient, pineconeClient, sparseEncoder, new RagProperties());
    }
}
