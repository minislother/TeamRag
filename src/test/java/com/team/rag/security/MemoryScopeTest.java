package com.team.rag.security;
import com.team.rag.config.RagProperties;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
@DisplayName("会话身份作用域测试")
class MemoryScopeTest {
 @AfterEach void clear(){SecurityContextHolder.clearContext();}
 private void login(String name,List<String> projects){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new AuthenticatedUser(name,"USER",projects),null,List.of()));}
 @Test @DisplayName("会话隔离 - 用户、项目及权限变更均改变作用域") void testScope_IdentityAndAcl(){
  // Given
  RagProperties p=new RagProperties();p.getSecurity().setEnabled(true);AccessControlService a=new AccessControlService(p);
  login("alice",List.of("x"));String original=a.sessionScope("same","x");
  // When / Then
  login("bob",List.of("x"));assertThat(a.sessionScope("same","x")).isNotEqualTo(original);
  login("alice",List.of("x","y"));assertThat(a.sessionScope("same","x")).isNotEqualTo(original);
  login("alice",List.of("x"));assertThat(a.sessionScope("same","y")).isNotEqualTo(original);
  assertThatThrownBy(a::requireDocumentWrite).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
 }
}
