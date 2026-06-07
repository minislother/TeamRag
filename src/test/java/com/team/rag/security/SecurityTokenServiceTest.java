package com.team.rag.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.config.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("安全 Token 服务单元测试")
class SecurityTokenServiceTest {

    @Test
    @DisplayName("签发和解析 Token - 成功还原用户信息")
    void testIssueAndParse_Success() {
        // Given
        SecurityTokenService service = new SecurityTokenService(propertiesWithStrongSecret(), new ObjectMapper());
        AuthenticatedUser user = new AuthenticatedUser("alice", "USER", List.of("infra"));

        // When
        String token = service.issue(user);
        AuthenticatedUser parsed = service.parse(token);

        // Then
        assertThat(parsed.username()).isEqualTo("alice");
        assertThat(parsed.role()).isEqualTo("USER");
        assertThat(parsed.projects()).containsExactly("infra");
    }

    @Test
    @DisplayName("解析 Token - 签名被篡改时拒绝")
    void testParse_TamperedToken_ThrowException() {
        // Given
        SecurityTokenService service = new SecurityTokenService(propertiesWithStrongSecret(), new ObjectMapper());
        String token = service.issue(new AuthenticatedUser("alice", "USER", List.of("infra"))) + "x";

        // When & Then
        assertThatThrownBy(() -> service.parse(token))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("解析 Token - 缺少用户身份时拒绝")
    void testParse_MissingSubject_ThrowException() throws Exception {
        // Given
        RagProperties properties = propertiesWithStrongSecret();
        SecurityTokenService service = new SecurityTokenService(properties, new ObjectMapper());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("role", "USER");
        payload.put("projects", List.of("infra"));
        payload.put("exp", Instant.now().plusSeconds(60).getEpochSecond());
        String token = token(properties.getSecurity().getTokenSecret(), payload);

        // When & Then
        assertThatThrownBy(() -> service.parse(token))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("安全配置 - 启用安全时必须配置生产密钥")
    void testValidateConfig_DefaultSecret_ThrowException() {
        // Given
        RagProperties properties = new RagProperties();
        properties.getSecurity().setEnabled(true);
        SecurityTokenService service = new SecurityTokenService(properties, new ObjectMapper());

        // When & Then
        assertThatThrownBy(service::validateConfig)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RAG_SECURITY_TOKEN_SECRET");
    }

    private RagProperties propertiesWithStrongSecret() {
        RagProperties properties = new RagProperties();
        properties.getSecurity().setTokenSecret("0123456789abcdef0123456789abcdef");
        return properties;
    }

    private String token(String secret, Map<String, Object> payload) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String header = encode(mapper.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
        String body = encode(mapper.writeValueAsBytes(payload));
        String unsignedToken = header + "." + body;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return unsignedToken + "." + encode(mac.doFinal(unsignedToken.getBytes(StandardCharsets.UTF_8)));
    }

    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
