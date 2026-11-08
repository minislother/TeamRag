package com.team.rag.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.config.RagProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SecurityTokenService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final RagProperties properties;
    private final ObjectMapper objectMapper;

    @PostConstruct
    void validateConfig() {
        if (!Boolean.TRUE.equals(properties.getSecurity().getEnabled())) {
            return;
        }
        String tenant = properties.getSecurity().getTenantId();
        if (tenant == null || !tenant.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalStateException("RAG_TENANT_ID 格式无效");
        String secret = properties.getSecurity().getTokenSecret();
        if (!StringUtils.hasText(secret) || secret.length() < 32 || secret.startsWith("change-this")) {
            throw new IllegalStateException("RAG_SECURITY_TOKEN_SECRET must be set to a strong custom value when security is enabled");
        }
    }

    public String issue(AuthenticatedUser user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(properties.getSecurity().getTokenTtlMinutes() * 60);
        Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", user.username());
        payload.put("tenant", properties.getSecurity().getTenantId());
        payload.put("role", user.role());
        payload.put("projects", user.projects());
        payload.put("iat", now.getEpochSecond());
        payload.put("exp", expiresAt.getEpochSecond());
        String unsignedToken = encodeJson(header) + "." + encodeJson(payload);
        return unsignedToken + "." + sign(unsignedToken);
    }

    @SuppressWarnings("unchecked")
    public AuthenticatedUser parse(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new BadCredentialsException("无效登录凭证");
            }
            String unsignedToken = parts[0] + "." + parts[1];
            if (!constantTimeEquals(parts[2], sign(unsignedToken))) {
                throw new BadCredentialsException("无效登录凭证");
            }
            Map<String, Object> payload = objectMapper.readValue(decode(parts[1]), MAP_TYPE);
            if (!properties.getSecurity().getTenantId().equals(payload.get("tenant"))) {
                throw new BadCredentialsException("登录凭证租户不匹配");
            }
            long exp = ((Number) payload.getOrDefault("exp", 0)).longValue();
            if (Instant.now().getEpochSecond() >= exp) {
                throw new BadCredentialsException("登录已过期");
            }
            Object projectsValue = payload.get("projects");
            List<String> projects = projectsValue instanceof List<?> values
                    ? values.stream().map(String::valueOf).toList()
                    : List.of();
            String username = String.valueOf(payload.get("sub"));
            String role = String.valueOf(payload.get("role"));
            if (!StringUtils.hasText(username) || "null".equals(username) || !StringUtils.hasText(role) || "null".equals(role)) {
                throw new BadCredentialsException("无效登录凭证");
            }
            return new AuthenticatedUser(
                    username,
                    role,
                    projects
            );
        } catch (IOException | IllegalArgumentException ex) {
            throw new BadCredentialsException("无效登录凭证", ex);
        }
    }

    private String encodeJson(Map<String, Object> value) {
        try {
            return encode(objectMapper.writeValueAsBytes(value));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Token 序列化失败", ex);
        }
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(properties.getSecurity().getTokenSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return encode(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Token 签名失败", ex);
        }
    }

    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigestSupport.equals(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static final class MessageDigestSupport {

        private static boolean equals(byte[] left, byte[] right) {
            return java.security.MessageDigest.isEqual(left, right);
        }
    }
}
