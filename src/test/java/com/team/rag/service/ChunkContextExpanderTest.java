package com.team.rag.service;
import com.team.rag.bean.*;
import com.team.rag.config.RagProperties;
import com.team.rag.repository.DocumentLifecycleRepository;
import com.team.rag.security.AccessControlService;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
@DisplayName("父子上下文扩展测试")
class ChunkContextExpanderTest {
 @Test @DisplayName("兜底子块 - 恢复父块并去除重叠及重复命中")
 void testExpand_HardLimit_MergeAndDeduplicate() {
  // Given
  DocumentLifecycleRepository repo=mock(DocumentLifecycleRepository.class);
  AccessControlService access=mock(AccessControlService.class);
  when(repo.findDocument("d")).thenReturn(Optional.of(RagDocument.builder().id("d").project("p").status(DocumentStatus.ACTIVE).build()));
  when(repo.findVersion("d",1)).thenReturn(Optional.of(DocumentVersion.builder().status(DocumentStatus.ACTIVE).build()));
  when(access.canAccessProject("p")).thenReturn(true);
  Map<String,Object> a=Map.of("documentId","d","version",1,"parentId","p1","position",0,"startOffset",0,"endOffset",4,"splitReason","HARD_LIMIT");
  Map<String,Object> b=Map.of("documentId","d","version",1,"parentId","p1","position",1,"startOffset",2,"endOffset",6,"splitReason","HARD_LIMIT");
  when(repo.findChunks("d",1)).thenReturn(List.of(StoredDocumentChunk.builder().vectorId("a").text("abcd").metadata(a).build(),StoredDocumentChunk.builder().vectorId("b").text("cdef").metadata(b).build()));
  List<RetrievedSegment> hits=List.of(RetrievedSegment.builder().id("b").text("cdef").metadata(b).build(),RetrievedSegment.builder().id("a").text("abcd").metadata(a).build());
  // When
  List<RetrievedSegment> result=new ChunkContextExpander(repo,access,new RagProperties()).expand(hits);
  // Then
  assertThat(result).hasSize(1); assertThat(result.get(0).getText()).isEqualTo("abcdef");
 }
 @Test @DisplayName("旧索引 - 无关系元数据时保留命中且不访问仓储")
 void testExpand_LegacyHit_Preserve() {
  // Given
  DocumentLifecycleRepository repo=mock(DocumentLifecycleRepository.class);
  RetrievedSegment hit=RetrievedSegment.builder().id("old").text("正文").metadata(Map.of()).build();
  // When
  List<RetrievedSegment> result=new ChunkContextExpander(repo,mock(AccessControlService.class),new RagProperties()).expand(List.of(hit));
  // Then
  assertThat(result.get(0).getText()).isEqualTo("正文"); verifyNoInteractions(repo);
 }
}
