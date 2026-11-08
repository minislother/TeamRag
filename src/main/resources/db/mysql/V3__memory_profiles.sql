-- 一个部署一个租户；owner_id 由服务端 tenant:user 生成，不接收客户端 owner。
-- 每条结构化记忆包含类型、键、来源、置信度、版本及过期时间。
CREATE TABLE rag_memory_profiles (
 owner_id VARCHAR(256) PRIMARY KEY,
 state_json LONGTEXT NOT NULL,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 防止两个租户误连同一个生命周期数据库；单租户部署模型下拒绝混用。
CREATE TABLE rag_deployment_tenant (
 singleton_id INT PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL
);
