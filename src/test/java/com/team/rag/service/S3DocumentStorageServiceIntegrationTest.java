package com.team.rag.service;

import com.team.rag.config.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.InputStream;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("S3/MinIO 文档存储集成测试")
class S3DocumentStorageServiceIntegrationTest {

    private static final String ACCESS_KEY = "minioadmin";
    private static final String SECRET_KEY = "minioadmin123";
    private static final String BUCKET = "team-rag-it";

    @Container
    private static final GenericContainer<?> MINIO = new GenericContainer<>(
            DockerImageName.parse("minio/minio:RELEASE.2024-01-16T16-07-38Z"))
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withCommand("server /data");

    @Test
    @DisplayName("保存读取删除文档 - 真实 MinIO 对象存储成功")
    void testStoreOpenDelete_RealMinio_Success() throws Exception {
        // Given
        String endpoint = endpoint();
        try (S3Client setupClient = s3Client(endpoint)) {
            setupClient.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        }
        RagProperties properties = s3Properties(endpoint);
        S3DocumentStorageService service = new S3DocumentStorageService(properties);
        byte[] content = "Redis 缓存穿透可以用布隆过滤器、空值缓存和参数校验处理".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "redis.txt", "text/plain", content);

        // When
        StoredDocumentFile stored = service.store(file, "doc-it", 1);

        // Then
        assertThat(stored.path()).isEqualTo("s3://" + BUCKET + "/it/doc-it/1/redis.txt");
        assertThat(stored.fileHash()).hasSize(64);
        assertThat(stored.size()).isEqualTo(content.length);
        try (InputStream inputStream = service.open(stored.path())) {
            assertThat(inputStream.readAllBytes()).isEqualTo(content);
        }

        service.deleteQuietly(stored);

        try (S3Client verifyClient = s3Client(endpoint)) {
            assertThatThrownBy(() -> verifyClient.headObject(HeadObjectRequest.builder()
                    .bucket(BUCKET)
                    .key("it/doc-it/1/redis.txt")
                    .build()))
                    .isInstanceOf(S3Exception.class)
                    .extracting(ex -> ((S3Exception) ex).statusCode())
                    .isEqualTo(404);
        }
    }

    private RagProperties s3Properties(String endpoint) {
        RagProperties properties = new RagProperties();
        RagProperties.S3 s3 = properties.getDocumentStorage().getS3();
        s3.setEndpoint(endpoint);
        s3.setBucket(BUCKET);
        s3.setAccessKey(ACCESS_KEY);
        s3.setSecretKey(SECRET_KEY);
        s3.setPathStyleAccess(true);
        s3.setPrefix("it");
        return properties;
    }

    private String endpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    private S3Client s3Client(String endpoint) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .build();
    }
}
