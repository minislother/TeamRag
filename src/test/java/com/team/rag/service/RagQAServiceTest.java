package com.team.rag.service;

import com.team.rag.bean.RagQo;
import com.team.rag.bean.RetrievedSegment;
import com.team.rag.client.DashScopeClient;
import com.team.rag.config.RagProperties;
import com.team.rag.security.AccessControlService;
import com.team.rag.security.AuthenticatedUser;
import com.team.rag.util.BM25Retriever;
import com.team.rag.util.PromptBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagQAServiceTest {

    @Mock
    private ShortTermMemoryService shortTermMemoryService;
    @Mock
    private QueryRewriteService queryRewriteService;
    @Mock
    private LongTermMemoryService longTermMemoryService;
    @Mock
    private BM25Retriever bm25Retriever;
    @Mock
    private DashScopeClient dashScopeClient;

    private RagProperties properties;
    private RagQAService ragQAService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        properties = new RagProperties();
        ragQAService = new RagQAService(
                shortTermMemoryService,
                queryRewriteService,
                longTermMemoryService,
                bm25Retriever,
                new PromptBuilder(),
                dashScopeClient,
                properties,
                new AccessControlService(properties)
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("RAG 问答 - 知识库无命中时转通用大模型兜底")
    void testQa_NoKnowledgeHit_UseGeneralFallback() {
        // Given
        RagQo qo = new RagQo();
        qo.setQuery("Redis 缓存穿透如何解决");
        qo.setConversationId("demo");
        when(shortTermMemoryService.getRecentMessages("demo")).thenReturn(List.of());
        when(queryRewriteService.rewrite(qo.getQuery(), List.of())).thenReturn(qo.getQuery());
        when(longTermMemoryService.recall(eq(qo.getQuery()), anyMap())).thenReturn(List.of());
        when(bm25Retriever.retrieve(eq(qo.getQuery()), isNull(), eq(properties.getPinecone().getNamespace()), org.mockito.ArgumentMatchers.anyMap())).thenReturn(List.of());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(dashScopeClient.stream(promptCaptor.capture())).thenReturn(Flux.just("可以缓存空值、使用布隆过滤器或做参数校验。"));

        // When & Then
        StepVerifier.create(ragQAService.qa(qo))
                .expectNext("可以缓存空值、使用布隆过滤器或做参数校验。")
                .verifyComplete();

        assertThat(promptCaptor.getValue())
                .isEqualTo("请回答问题：Redis 缓存穿透如何解决")
                .doesNotContain("检索资料");
        verify(shortTermMemoryService).appendExchange(
                eq("demo"),
                eq(qo.getQuery()),
                eq("可以缓存空值、使用布隆过滤器或做参数校验。")
        );
    }

    @Test
    @DisplayName("RAG 问答 - 知识库命中时不使用通用兜底")
    void testQa_KnowledgeHit_UseRagPrompt() {
        // Given
        RagQo qo = new RagQo();
        qo.setQuery("Redis 缓存穿透如何解决");
        qo.setConversationId("demo");
        RetrievedSegment segment = RetrievedSegment.builder()
                .id("doc-1")
                .score(0.92)
                .text("缓存穿透可以通过缓存空值、布隆过滤器、参数校验解决。")
                .metadata(Map.of("filename", "redis.md", "docType", "研发规范"))
                .build();
        when(shortTermMemoryService.getRecentMessages("demo")).thenReturn(List.of());
        when(queryRewriteService.rewrite(qo.getQuery(), List.of())).thenReturn(qo.getQuery());
        when(longTermMemoryService.recall(eq(qo.getQuery()), anyMap())).thenReturn(List.of());
        when(bm25Retriever.retrieve(eq(qo.getQuery()), isNull(), eq(properties.getPinecone().getNamespace()), org.mockito.ArgumentMatchers.anyMap())).thenReturn(List.of(segment));
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(dashScopeClient.stream(promptCaptor.capture())).thenReturn(Flux.just("根据知识库，缓存穿透可以通过缓存空值、布隆过滤器、参数校验解决。"));

        // When & Then
        StepVerifier.create(ragQAService.qa(qo))
                .expectNext("根据知识库，缓存穿透可以通过缓存空值、布隆过滤器、参数校验解决。")
                .verifyComplete();

        assertThat(promptCaptor.getValue())
                .contains("1、缓存穿透可以通过缓存空值、布隆过滤器、参数校验解决。")
                .contains("知识库中未找到相关答案")
                .contains("用户问题：Redis 缓存穿透如何解决")
                .doesNotContain("请回答问题：");
        verify(shortTermMemoryService).appendExchange(
                eq("demo"),
                eq(qo.getQuery()),
                eq("根据知识库，缓存穿透可以通过缓存空值、布隆过滤器、参数校验解决。")
        );
    }

    @Test
    @DisplayName("RAG 问答 - 未传会话信息时默认使用 memoryId 1")
    void testQa_NoConversation_UseDefaultMemoryId() {
        // Given
        RagQo qo = new RagQo();
        qo.setQuery("Redis 是什么");
        when(shortTermMemoryService.getRecentMessages("1")).thenReturn(List.of());
        when(queryRewriteService.rewrite(qo.getQuery(), List.of())).thenReturn(qo.getQuery());
        when(longTermMemoryService.recall(eq(qo.getQuery()), anyMap())).thenReturn(List.of());
        when(bm25Retriever.retrieve(eq(qo.getQuery()), isNull(), eq(properties.getPinecone().getNamespace()), org.mockito.ArgumentMatchers.anyMap())).thenReturn(List.of());
        when(dashScopeClient.stream(anyString())).thenReturn(Flux.just("Redis 是内存数据库。"));

        // When & Then
        StepVerifier.create(ragQAService.qa(qo))
                .expectNext("Redis 是内存数据库。")
                .verifyComplete();

        verify(shortTermMemoryService).appendExchange(
                eq("1"),
                eq(qo.getQuery()),
                eq("Redis 是内存数据库。")
        );
    }

    @Test
    @DisplayName("RAG 问答 - 检索时携带 ACTIVE 和项目过滤条件")
    @SuppressWarnings("unchecked")
    void testQa_WithProject_UseActiveFilter() {
        // Given
        RagQo qo = new RagQo();
        qo.setQuery("Redis 是什么");
        qo.setProject("infra");
        when(shortTermMemoryService.getRecentMessages("1")).thenReturn(List.of());
        when(queryRewriteService.rewrite(qo.getQuery(), List.of())).thenReturn(qo.getQuery());
        when(longTermMemoryService.recall(eq(qo.getQuery()), anyMap())).thenReturn(List.of());
        ArgumentCaptor<Map<String, Object>> filterCaptor = ArgumentCaptor.forClass(Map.class);
        when(bm25Retriever.retrieve(eq(qo.getQuery()), isNull(), eq(properties.getPinecone().getNamespace()), filterCaptor.capture()))
                .thenReturn(List.of());
        when(dashScopeClient.stream("请回答问题：Redis 是什么")).thenReturn(Flux.just("Redis 是内存数据库。"));

        // When & Then
        StepVerifier.create(ragQAService.qa(qo))
                .expectNext("Redis 是内存数据库。")
                .verifyComplete();

        assertThat(filterCaptor.getValue())
                .containsEntry("status", Map.of("$eq", "ACTIVE"))
                .containsEntry("project", Map.of("$eq", "infra"));
    }

    @Test
    @DisplayName("RAG 问答 - 受限用户未选择项目时使用项目白名单过滤")
    @SuppressWarnings("unchecked")
    void testQa_RestrictedUser_UseProjectAllowListFilter() {
        // Given
        properties.getSecurity().setEnabled(true);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser("alice", "USER", List.of("infra", "docs")),
                null,
                List.of()
        ));
        RagQo qo = new RagQo();
        qo.setQuery("Redis 是什么");
        when(shortTermMemoryService.getRecentMessages("1")).thenReturn(List.of());
        when(queryRewriteService.rewrite(qo.getQuery(), List.of())).thenReturn(qo.getQuery());
        ArgumentCaptor<Map<String, Object>> memoryFilterCaptor = ArgumentCaptor.forClass(Map.class);
        when(longTermMemoryService.recall(eq(qo.getQuery()), memoryFilterCaptor.capture())).thenReturn(List.of());
        ArgumentCaptor<Map<String, Object>> documentFilterCaptor = ArgumentCaptor.forClass(Map.class);
        when(bm25Retriever.retrieve(eq(qo.getQuery()), isNull(), eq(properties.getPinecone().getNamespace()), documentFilterCaptor.capture()))
                .thenReturn(List.of());
        when(dashScopeClient.stream("请回答问题：Redis 是什么")).thenReturn(Flux.just("Redis 是内存数据库。"));

        // When & Then
        StepVerifier.create(ragQAService.qa(qo))
                .expectNext("Redis 是内存数据库。")
                .verifyComplete();

        assertThat(memoryFilterCaptor.getValue()).containsEntry("project", Map.of("$in", List.of("infra", "docs")));
        assertThat(documentFilterCaptor.getValue())
                .containsEntry("status", Map.of("$eq", "ACTIVE"))
                .containsEntry("project", Map.of("$in", List.of("infra", "docs")));
    }
}
