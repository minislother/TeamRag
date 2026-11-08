# AI README - 项目规则入口

## 项目总览
TeamRagKnowledgeBase 是一个面向 baituan 社团资料管理与问答的 Spring Boot RAG 知识库，核心能力是社团资料入库、语义分块、DashScope embedding、Pinecone hybrid 检索和 glm-5 流式问答。

```mermaid
flowchart LR
 Client[浏览器或 API 客户端] --> Web[Spring Boot 接口]
 Web --> Doc[文档入库流程]
 Web --> QA[RAG 问答流程]
 Doc --> Pinecone[Pinecone 文档 namespace]
 QA --> Pinecone
 QA --> DashScope[DashScope text-embedding-v3 / glm-5]
```

## 生成信息
- 生成时间：2026-06-04 20:09
- 生成分支：非 Git 仓库

## 快速导航
### AI 生成文档（generated/）
- [x] [项目结构](./generated/项目结构.md) - 目录树、模块划分；了解代码组织时使用 (2026-06-04 20:09)
- [x] [技术架构](./generated/技术架构.md) - 分层架构、技术栈；了解技术选型时使用 (2026-06-04 20:09)
- [x] [开发指南](./generated/开发指南.md) - 环境搭建、构建/启动命令；上手时使用 (2026-06-04 20:09)
- [x] [核心流程](./generated/核心流程.md) - 主要业务调用链；理解系统时使用 (2026-06-04 20:09)

### 人工维护文档（manual/）
- [ ] [业务知识](./manual/业务知识.md) - 项目背景、领域术语、业务规则
- [ ] [历史经验](./manual/历史经验.md) - 踩坑记录；写代码前必读

## 使用方式
AI Agent 进入项目时先读项目根目录 `AGENTS.md`，再按任务类型阅读本目录下的 generated 或 manual 文档。

```text
入口 AGENTS.md -> 本 README -> generated 技术事实 -> manual 团队补充
```
