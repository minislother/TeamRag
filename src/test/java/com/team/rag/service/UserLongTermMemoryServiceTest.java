package com.team.rag.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("个人记忆治理与隔离测试")
class UserLongTermMemoryServiceTest {
 private UserLongTermMemoryService service;
 @BeforeEach void setUp(){service=new UserLongTermMemoryService(new ObjectMapper().findAndRegisterModules(),null);}
 @Test @DisplayName("用户隔离 - 相同键不会跨用户读取") void testMemory_OwnerIsolation(){
  // Given
  service.put("tenant:alice","preference","style","简洁回答");
  // When
  String other=service.context("tenant:bob","回答");
  // Then
  assertThat(other).isEmpty(); assertThat(service.list("tenant:bob")).isEmpty();
 }
 @Test @DisplayName("偏好冲突 - 显式覆盖并保留版本，删除清除历史") void testMemory_UpdateDelete(){
  // Given
  var first=service.put("t:a","preference","style","详细回答");
  // When
  var second=service.put("t:a","preference","style","简洁回答");
  // Then
  assertThat(second.version()).isEqualTo(2);assertThat(service.list("t:a")).hasSize(1);
  assertThat(service.history("t:a")).containsExactly(first);
  service.delete("t:a",second.id());assertThat(service.context("t:a","回答")).isEmpty();assertThat(service.history("t:a")).isEmpty();
 }
 @Test @DisplayName("写入筛选 - 普通要求不记忆，明确偏好才记忆，暂停禁止读写") void testMemory_ExplicitAndPause(){
  // Given
  service.extractAndStore("t:a","必须查一下报销规则");
  // When
  assertThat(service.list("t:a")).isEmpty();service.extractAndStore("t:a","以后请简短回答");
  // Then
  assertThat(service.context("t:a","你好")).contains("简短回答");
  service.pause("t:a",true); assertThat(service.context("t:a","你好")).isEmpty();
  service.extractAndStore("t:a","请记住其他事情");assertThat(service.list("t:a")).hasSize(1);
  service.clear("t:a");service.pause("t:a",false);assertThat(service.list("t:a")).isEmpty();
 }
 @Test @DisplayName("输入边界 - 匿名和凭证不能存为个人记忆") void testMemory_RejectSensitiveAndAnonymous(){
  // Given / When / Then
  assertThatThrownBy(()->service.put("","fact","x","资料")).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->service.put("t:a","fact","x","我的密码是123")).isInstanceOf(IllegalArgumentException.class);
 }
}
