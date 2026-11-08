package com.team.rag.util;
import com.team.rag.bean.DocumentSegment;
import com.team.rag.client.DashScopeClient;
import com.team.rag.config.RagProperties;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("结构化父子分块测试")
class AdaptiveChunkerTest {
 @Test @DisplayName("短章节 - 保留结构且不调用向量模型")
 void testSplit_ShortSections_PreserveStructure() {
  // Given
  DashScopeClient client=mock(DashScopeClient.class);
  SemanticSplitter splitter=new SemanticSplitter(client,new RagProperties());
  String text="# 第一章\n正文。\n# 第二章\n内容。";
  // When
  List<DocumentSegment> chunks=splitter.splitAdaptive("doc",text,Map.of("version",2));
  // Then
  assertThat(chunks).hasSize(2);
  assertThat(chunks.stream().map(DocumentSegment::getText).reduce("",String::concat)).isEqualTo(text);
  verifyNoInteractions(client);
 }
 @Test @DisplayName("超长连续正文 - token上限及偏移可无损还原")
 void testSplit_LongUnit_RestoreOriginal() {
  // Given
  RagProperties properties=new RagProperties();
  properties.getSemanticSplitter().setMaxTokens(30);
  properties.getSemanticSplitter().setOverlapTokens(5);
  String text="中文连续知识点与数据库一致性".repeat(40);
  SemanticSplitter splitter=new SemanticSplitter(mock(DashScopeClient.class),properties);
  // When
  List<DocumentSegment> chunks=splitter.splitAdaptive("doc",text,Map.of("version",3));
  // Then
  assertThat(chunks.size()).isGreaterThan(1);
  StringBuilder restored=new StringBuilder(); int end=0;
  for(DocumentSegment c:chunks) {
   assertThat(AdaptiveChunker.tokens(c.getText())).isLessThanOrEqualTo(30);
   assertThat(c.getMetadata().get("splitReason")).isEqualTo("HARD_LIMIT");
   int start=((Number)c.getMetadata().get("startOffset")).intValue();
   restored.append(c.getText().substring(Math.max(0,end-start)));
   end=((Number)c.getMetadata().get("endOffset")).intValue();
  }
  assertThat(restored.toString()).isEqualTo(text);
  assertThat(chunks.stream().map(c->c.getMetadata().get("parentId")).distinct().count()).isEqualTo(1);
 }

 @Test @DisplayName("分类分块 - FAQ问答与表格独立保留标题层级和权限")
 void testSplit_FaqAndTable_PreserveMetadata() {
  // Given
  SemanticSplitter splitter=new SemanticSplitter(mock(DashScopeClient.class),new RagProperties());
  String text="# 总则\n## 常见问题\nQ: 如何申请？\nA: 在线申请。\nQ: 谁审批？\nA: 管理员。\n|字段|说明|\n|---|---|\n|姓名|必填|\n";
  Map<String,Object> base=Map.of("docType","FAQ","updatedAt","2026-11-08","permissionTags",List.of("project:p"));
  // When
  List<DocumentSegment> chunks=splitter.splitAdaptive("d",text,base);
  // Then
  assertThat(chunks).anySatisfy(c->{assertThat(c.getText()).contains("Q: 如何申请？","A: 在线申请。");assertThat(c.getText()).doesNotContain("Q: 谁审批？");});
  assertThat(chunks).anySatisfy(c->{assertThat(c.getMetadata().get("chunkType")).isEqualTo("TABLE");assertThat(c.getMetadata().get("titlePath")).isEqualTo(List.of("总则","常见问题"));});
  assertThat(chunks).allSatisfy(c->assertThat(c.getMetadata()).containsEntry("permissionTags",List.of("project:p")).containsEntry("updatedAt","2026-11-08"));
 }
 @Test @DisplayName("结构解析 - HTML标题与表格独立抽取")
 void testParse_Html_KeepStructure() throws Exception {
  // Given
  String html="<html><body><h1>制度</h1><p>说明</p><table><tr><th>项目</th><th>金额</th></tr><tr><td>活动</td><td>100</td></tr></table></body></html>";
  // When
  String text=StructuredDocumentParser.parse(new java.io.ByteArrayInputStream(html.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"policy.html");
  // Then
  assertThat(text).contains("# 制度","|项目|金额|","|活动|100|");
 }
 @Test @DisplayName("制度分块 - 按段落边界拆分并继承标题")
 void testSplit_Policy_ParagraphBoundaries() {
  // Given
  SemanticSplitter splitter=new SemanticSplitter(mock(DashScopeClient.class),new RagProperties());
  // When
  List<DocumentSegment> chunks=splitter.splitAdaptive("d","# 章程\n第一段。\n\n第二段。",Map.of("docType","社团章程"));
  // Then
  assertThat(chunks).hasSize(2);
  assertThat(chunks.get(1).getMetadata().get("headingPath")).isEqualTo("章程");
  assertThat(chunks.get(0).getText()).doesNotContain("第二段");
 }
}
