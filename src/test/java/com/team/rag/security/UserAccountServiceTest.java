package com.team.rag.security;

import com.team.rag.config.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("用户账号服务单元测试")
class UserAccountServiceTest {

    @Test
    @DisplayName("认证用户 - 成功解析角色和项目权限")
    void testAuthenticate_Success_ReturnUserProjects() {
        // Given
        RagProperties properties = new RagProperties();
        properties.getSecurity().setUsers("alice:secret:USER:infra|docs");
        UserAccountService service = new UserAccountService(properties);

        // When
        AuthenticatedUser user = service.authenticate("alice", "secret");

        // Then
        assertThat(user.username()).isEqualTo("alice");
        assertThat(user.role()).isEqualTo("USER");
        assertThat(user.projects()).containsExactly("infra", "docs");
    }

    @Test
    @DisplayName("认证用户 - 密码错误时抛出认证异常")
    void testAuthenticate_WrongPassword_ThrowException() {
        // Given
        RagProperties properties = new RagProperties();
        UserAccountService service = new UserAccountService(properties);

        // When & Then
        assertThatThrownBy(() -> service.authenticate("admin", "wrong"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("用户名或密码错误");
    }

    @Test
    @DisplayName("查询用户 - 用户存在时返回账号")
    void testRequireUser_UserExists_ReturnAccount() {
        // Given
        RagProperties properties = new RagProperties();
        properties.getSecurity().setUsers("alice:secret:USER:infra");
        UserAccountService service = new UserAccountService(properties);

        // When
        UserAccountService.UserAccount account = service.requireUser("ALICE");

        // Then
        assertThat(account.username()).isEqualTo("alice");
        assertThat(account.projects()).containsExactly("infra");
    }

    @Test
    @DisplayName("查询用户 - 用户不存在时抛出认证异常")
    void testRequireUser_UserMissing_ThrowException() {
        // Given
        RagProperties properties = new RagProperties();
        UserAccountService service = new UserAccountService(properties);

        // When & Then
        assertThatThrownBy(() -> service.requireUser("missing"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("登录用户不存在");
    }
}
