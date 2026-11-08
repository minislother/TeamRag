# baituan 社团资料种子库

这是一组用于 TeamRagKnowledgeBase 的 baituan 社团资料种子文档，覆盖社团介绍、章程、招新、活动立项、场地物资、预算报销、安全预案、数据统计、评价复盘等常见问答场景。

## 使用方式

1. 启动本项目：

```powershell
mvn spring-boot:run
```

2. 批量上传本目录资料：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/import-baituan-club-knowledge.ps1 -BaseUrl "http://localhost:8080"
```

3. 上传完成后可以测试：

```text
社团招新活动需要提前准备哪些材料？
社团活动立项需要哪些审批？
活动预算报销有什么要求？
社团换届流程怎么走？
```

## 资料组织

`manifest.csv` 记录每份资料的文件名、资料类型、项目、标签和说明。上传脚本会读取该清单并逐份调用 `/api/document/upload`。
