package com.team.rag.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.config.RagProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("Token 认证过滤器单元测试")
class TokenAuthenticationFilterTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("过滤请求 - 安全关闭时直接放行")
    void testDoFilter_SecurityDisabled_PassThrough() throws Exception {
        // Given
        RagProperties properties = new RagProperties();
        TokenAuthenticationFilter filter = new TokenAuthenticationFilter(
                properties,
                new SecurityTokenService(properties, new ObjectMapper())
        );
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        // When
        filter.doFilter(request, response, chain);

        // Then
        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("过滤请求 - 无 Token 时交给安全链处理")
    void testDoFilter_NoToken_PassThroughWithoutAuthentication() throws Exception {
        // Given
        RagProperties properties = secureProperties();
        TokenAuthenticationFilter filter = new TokenAuthenticationFilter(
                properties,
                new SecurityTokenService(properties, new ObjectMapper())
        );
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        // When
        filter.doFilter(request, response, chain);

        // Then
        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("过滤请求 - 有效 Token 写入认证上下文")
    void testDoFilter_ValidToken_SetAuthentication() throws Exception {
        // Given
        RagProperties properties = secureProperties();
        SecurityTokenService tokenService = new SecurityTokenService(properties, new ObjectMapper());
        TokenAuthenticationFilter filter = new TokenAuthenticationFilter(properties, tokenService);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + tokenService.issue(new AuthenticatedUser("alice", "USER", List.of("infra"))));
        MockHttpServletResponse response = new MockHttpServletResponse();

        // When
        filter.doFilter(request, response, chain);

        // Then
        verify(chain).doFilter(request, response);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(((AuthenticatedUser) authentication.getPrincipal()).username()).isEqualTo("alice");
        assertThat(authentication.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("过滤请求 - 无效 Token 返回 401")
    void testDoFilter_InvalidToken_ReturnUnauthorized() throws Exception {
        // Given
        RagProperties properties = secureProperties();
        TokenAuthenticationFilter filter = new TokenAuthenticationFilter(
                properties,
                new SecurityTokenService(properties, new ObjectMapper())
        );
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer invalid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // When
        filter.doFilter(request, response, chain);

        // Then
        verify(chain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("无效或已过期的登录凭证");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private RagProperties secureProperties() {
        RagProperties properties = new RagProperties();
        properties.getSecurity().setEnabled(true);
        properties.getSecurity().setTokenSecret("0123456789abcdef0123456789abcdef");
        return properties;
    }
}
