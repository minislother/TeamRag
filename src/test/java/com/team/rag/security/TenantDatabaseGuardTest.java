package com.team.rag.security;
import com.team.rag.config.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
@DisplayName("部署租户数据库边界测试")
class TenantDatabaseGuardTest {
 @Test @DisplayName("数据库已绑定其他租户 - 拒绝启动") void testGuard_OtherTenant_Reject(){
  // Given
  RagProperties p=new RagProperties();p.getSecurity().setEnabled(true);p.getSecurity().setTenantId("a");
  JdbcTemplate jdbc=mock(JdbcTemplate.class);
  when(jdbc.queryForObject("SELECT tenant_id FROM rag_deployment_tenant WHERE singleton_id=1",String.class)).thenReturn("b");
  // When / Then
  assertThatThrownBy(()->new TenantDatabaseGuard(p,jdbc).validate()).isInstanceOf(IllegalStateException.class);
 }
}
