package com.team.rag.service;

import com.team.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 保存原始上传文件，并计算文件 hash 供版本去重使用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.document-storage", name = "type", havingValue = "local", matchIfMissing = true)
public class LocalDocumentStorageService implements DocumentStorageService {

    private final RagProperties properties;

    /**
     * 将上传文件保存到 documentId/version 目录下。
     */
    public StoredDocumentFile store(MultipartFile file, String documentId, Integer version) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Path targetDir = Path.of(properties.getDocument().getStorageDir(), documentId, String.valueOf(version));
            Files.createDirectories(targetDir);
            Path target = targetDir.resolve(cleanFilename(file.getOriginalFilename()));
            try (InputStream inputStream = new DigestInputStream(file.getInputStream(), digest)) {
                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return new StoredDocumentFile(target.toAbsolutePath().toString(), HexFormat.of().formatHex(digest.digest()), file.getSize());
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("保存原始文档失败：" + ex.getMessage(), ex);
        }
    }

    @Override
    public InputStream open(String path) throws IOException {
        return Files.newInputStream(Path.of(path));
    }

    /**
     * 删除已经落盘但不需要继续入库的文件，例如重复上传同一内容时的临时版本文件。
     */
    public void deleteQuietly(StoredDocumentFile storedFile) {
        if (storedFile == null || !StringUtils.hasText(storedFile.path())) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(storedFile.path()));
        } catch (IOException ex) {
            log.warn("清理重复上传文件失败: {}", storedFile.path(), ex);
        }
    }

    private String cleanFilename(String filename) {
        String cleaned = StringUtils.cleanPath(StringUtils.hasText(filename) ? filename : "unknown");
        Path nameOnly = Path.of(cleaned).getFileName();
        String safeName = nameOnly == null ? "unknown" : nameOnly.toString();
        return StringUtils.hasText(safeName) ? safeName : "unknown";
    }
}
