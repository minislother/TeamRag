package com.team.rag.config;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 单租户部署的数据库绑定检查：配置错误时启动失败，避免跨租户共享生命周期表。 */
@Component
public class TenantDatabaseGuard {
 private final RagProperties properties;
 private final JdbcTemplate jdbc;
 @Autowired public TenantDatabaseGuard(RagProperties properties,@Autowired(required=false) JdbcTemplate jdbc){this.properties=properties;this.jdbc=jdbc;}
 @PostConstruct public void validate(){
  if(!Boolean.TRUE.equals(properties.getSecurity().getEnabled())||jdbc==null)return;
  String tenant=properties.getSecurity().getTenantId();
  jdbc.update("INSERT IGNORE INTO rag_deployment_tenant(singleton_id,tenant_id) VALUES (1,?)",tenant);
  String bound=jdbc.queryForObject("SELECT tenant_id FROM rag_deployment_tenant WHERE singleton_id=1",String.class);
  if(!tenant.equals(bound))throw new IllegalStateException("生命周期数据库已绑定其他租户，拒绝启动");
 }
}
