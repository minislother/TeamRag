package com.team.rag.security;

import com.team.rag.config.RagProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("访问控制服务单元测试")
class AccessControlServiceTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("当前用户 - 安全关闭时返回本地管理员")
    void testCurrentUser_SecurityDisabled_ReturnLocalAdmin() {
        // Given
        AccessControlService service = new AccessControlService(new RagProperties());

        // When
        AuthenticatedUser user = service.currentUser();

        // Then
        assertThat(user.username()).isEqualTo("local");
        assertThat(user.canAccessAllProjects()).isTrue();
    }

    @Test
    @DisplayName("安全开关 - 返回当前配置状态")
    void testSecurityEnabled_ReturnConfiguredValue() {
        // Given
        RagProperties properties = new RagProperties();
        properties.getSecurity().setEnabled(true);
        AccessControlService service = new AccessControlService(properties);

        // When
        boolean enabled = service.securityEnabled();

        // Then
        assertThat(enabled).isTrue();
    }

    @Test
    @DisplayName("当前用户 - 安全开启但未登录时拒绝访问")
    void testCurrentUser_SecurityEnabledWithoutAuthentication_ThrowException() {
        // Given
        RagProperties properties = new RagProperties();
        properties.getSecurity().setEnabled(true);
        AccessControlService service = new AccessControlService(properties);

        // When & Then
        assertThatThrownBy(service::currentUser)
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("请先登录");
    }
}
