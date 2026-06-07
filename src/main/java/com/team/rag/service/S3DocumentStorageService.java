package com.team.rag.service;

import com.team.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * S3-compatible storage implementation for production object storage such as MinIO or OSS/S3.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.document-storage", name = "type", havingValue = "s3")
public class S3DocumentStorageService implements DocumentStorageService {

    private final RagProperties properties;
    private volatile S3Client s3Client;

    @Override
    public StoredDocumentFile store(MultipartFile file, String documentId, Integer version) {
        RagProperties.S3 s3 = requireS3Config();
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("team-rag-upload-", ".bin");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream inputStream = new DigestInputStream(file.getInputStream(), digest)) {
                Files.copy(inputStream, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            String key = objectKey(s3, documentId, version, file.getOriginalFilename());
            client().putObject(PutObjectRequest.builder()
                            .bucket(s3.getBucket())
                            .key(key)
                            .contentLength(file.getSize())
                            .build(),
                    RequestBody.fromFile(tempFile));
            return new StoredDocumentFile("s3://" + s3.getBucket() + "/" + key,
                    HexFormat.of().formatHex(digest.digest()),
                    file.getSize());
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("保存对象存储文件失败：" + ex.getMessage(), ex);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ex) {
                    log.warn("清理对象存储临时文件失败: {}", tempFile, ex);
                }
            }
        }
    }

    @Override
    public InputStream open(String path) {
        S3Location location = parseLocation(path);
        ResponseInputStream<GetObjectResponse> inputStream = client().getObject(GetObjectRequest.builder()
                .bucket(location.bucket())
                .key(location.key())
                .build());
        return inputStream;
    }

    @Override
    public void deleteQuietly(StoredDocumentFile storedFile) {
        if (storedFile == null || !StringUtils.hasText(storedFile.path())) {
            return;
        }
        try {
            S3Location location = parseLocation(storedFile.path());
            client().deleteObject(DeleteObjectRequest.builder()
                    .bucket(location.bucket())
                    .key(location.key())
                    .build());
        } catch (Exception ex) {
            log.warn("清理对象存储文件失败: {}", storedFile.path(), ex);
        }
    }

    private S3Client client() {
        S3Client current = s3Client;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (s3Client == null) {
                s3Client = buildClient(requireS3Config());
            }
            return s3Client;
        }
    }

    private S3Client buildClient(RagProperties.S3 s3) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(s3.getRegion()))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .forcePathStyle(Boolean.TRUE.equals(s3.getPathStyleAccess()));
        if (StringUtils.hasText(s3.getEndpoint())) {
            builder.endpointOverride(URI.create(s3.getEndpoint()));
        }
        if (StringUtils.hasText(s3.getAccessKey()) && StringUtils.hasText(s3.getSecretKey())) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(s3.getAccessKey(), s3.getSecretKey())));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }
        return builder.build();
    }

    private RagProperties.S3 requireS3Config() {
        RagProperties.S3 s3 = properties.getDocumentStorage().getS3();
        if (!StringUtils.hasText(s3.getBucket())) {
            throw new IllegalStateException("rag.document-storage.s3.bucket is required when document storage type is s3");
        }
        return s3;
    }

    private String objectKey(RagProperties.S3 s3, String documentId, Integer version, String originalFilename) {
        String prefix = StringUtils.hasText(s3.getPrefix()) ? trimSlashes(s3.getPrefix()) + "/" : "";
        return prefix + documentId + "/" + version + "/" + cleanFilename(originalFilename);
    }

    private String cleanFilename(String filename) {
        String cleaned = StringUtils.cleanPath(StringUtils.hasText(filename) ? filename : "unknown");
        Path nameOnly = Path.of(cleaned).getFileName();
        String safeName = nameOnly == null ? "unknown" : nameOnly.toString();
        return StringUtils.hasText(safeName) ? safeName : "unknown";
    }

    private String trimSlashes(String value) {
        return value.replaceAll("^/+", "").replaceAll("/+$", "");
    }

    private S3Location parseLocation(String path) {
        if (path.startsWith("s3://")) {
            URI uri = URI.create(path);
            String key = uri.getPath().startsWith("/") ? uri.getPath().substring(1) : uri.getPath();
            return new S3Location(uri.getHost(), key);
        }
        return new S3Location(requireS3Config().getBucket(), path);
    }

    private record S3Location(String bucket, String key) {
    }
}
