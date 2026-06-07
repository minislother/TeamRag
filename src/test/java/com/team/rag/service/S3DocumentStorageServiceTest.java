package com.team.rag.service;

import com.team.rag.config.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("S3 文档存储服务单元测试")
class S3DocumentStorageServiceTest {

    @Test
    @DisplayName("保存上传文件 - 上传到配置桶并计算哈希")
    void testStore_UploadToS3AndCalculateHash() {
        // Given
        RagProperties properties = s3Properties();
        properties.getDocumentStorage().getS3().setPrefix("/team-rag/");
        S3Client s3Client = mock(S3Client.class);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        S3DocumentStorageService service = service(properties, s3Client);
        byte[] content = "hello s3".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "../redis.txt", "text/plain", content);

        // When
        StoredDocumentFile stored = service.store(file, "doc-1", 2);

        // Then
        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));
        PutObjectRequest request = requestCaptor.getValue();
        assertThat(request.bucket()).isEqualTo("rag-bucket");
        assertThat(request.key()).isEqualTo("team-rag/doc-1/2/redis.txt");
        assertThat(request.contentLength()).isEqualTo(content.length);
        assertThat(stored.path()).isEqualTo("s3://rag-bucket/team-rag/doc-1/2/redis.txt");
        assertThat(stored.fileHash()).hasSize(64);
        assertThat(stored.size()).isEqualTo(content.length);
    }

    @Test
    @DisplayName("打开对象 - S3 路径使用路径中的桶和 Key")
    void testOpen_S3Uri_UseUriBucketAndKey() throws Exception {
        // Given
        S3Client s3Client = mock(S3Client.class);
        ResponseInputStream<GetObjectResponse> response = new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream("body".getBytes()))
        );
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(response);
        S3DocumentStorageService service = service(s3Properties(), s3Client);

        // When
        try (InputStream inputStream = service.open("s3://custom-bucket/docs/a.txt")) {
            // Then
            assertThat(inputStream.readAllBytes()).isEqualTo("body".getBytes());
        }
        ArgumentCaptor<GetObjectRequest> requestCaptor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(requestCaptor.capture());
        assertThat(requestCaptor.getValue().bucket()).isEqualTo("custom-bucket");
        assertThat(requestCaptor.getValue().key()).isEqualTo("docs/a.txt");
    }

    @Test
    @DisplayName("打开对象 - 普通路径使用配置桶")
    void testOpen_RelativePath_UseConfiguredBucket() {
        // Given
        S3Client s3Client = mock(S3Client.class);
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(new byte[0]))
        ));
        S3DocumentStorageService service = service(s3Properties(), s3Client);

        // When
        service.open("docs/a.txt");

        // Then
        ArgumentCaptor<GetObjectRequest> requestCaptor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(requestCaptor.capture());
        assertThat(requestCaptor.getValue().bucket()).isEqualTo("rag-bucket");
        assertThat(requestCaptor.getValue().key()).isEqualTo("docs/a.txt");
    }

    @Test
    @DisplayName("删除对象 - S3 路径解析后删除")
    void testDeleteQuietly_S3Uri_DeleteObject() {
        // Given
        S3Client s3Client = mock(S3Client.class);
        S3DocumentStorageService service = service(s3Properties(), s3Client);

        // When
        service.deleteQuietly(new StoredDocumentFile("s3://rag-bucket/doc-1/1/a.txt", "hash", 1));

        // Then
        ArgumentCaptor<DeleteObjectRequest> requestCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(requestCaptor.capture());
        assertThat(requestCaptor.getValue().bucket()).isEqualTo("rag-bucket");
        assertThat(requestCaptor.getValue().key()).isEqualTo("doc-1/1/a.txt");
    }

    @Test
    @DisplayName("删除对象 - 空路径直接跳过")
    void testDeleteQuietly_BlankPath_Skip() {
        // Given
        S3Client s3Client = mock(S3Client.class);
        S3DocumentStorageService service = service(s3Properties(), s3Client);

        // When
        service.deleteQuietly(null);
        service.deleteQuietly(new StoredDocumentFile("", "hash", 0));

        // Then
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("保存上传文件 - 未配置桶时抛出异常")
    void testStore_MissingBucket_ThrowException() {
        // Given
        RagProperties properties = new RagProperties();
        S3DocumentStorageService service = new S3DocumentStorageService(properties);
        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "body".getBytes());

        // When & Then
        assertThatThrownBy(() -> service.store(file, "doc-1", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bucket");
    }

    @Test
    @DisplayName("构建客户端 - 静态密钥和自定义 Endpoint 成功创建")
    void testBuildClient_StaticCredentialsAndEndpoint_ReturnClient() {
        // Given
        RagProperties properties = s3Properties();
        RagProperties.S3 s3 = properties.getDocumentStorage().getS3();
        s3.setEndpoint("http://127.0.0.1:9000");
        s3.setAccessKey("minioadmin");
        s3.setSecretKey("minioadmin123");
        S3DocumentStorageService service = new S3DocumentStorageService(properties);

        // When
        S3Client client = ReflectionTestUtils.invokeMethod(service, "buildClient", s3);

        // Then
        assertThat(client).isNotNull();
        client.close();
    }

    private S3DocumentStorageService service(RagProperties properties, S3Client s3Client) {
        S3DocumentStorageService service = new S3DocumentStorageService(properties);
        ReflectionTestUtils.setField(service, "s3Client", s3Client);
        return service;
    }

    private RagProperties s3Properties() {
        RagProperties properties = new RagProperties();
        properties.getDocumentStorage().getS3().setBucket("rag-bucket");
        return properties;
    }
}
