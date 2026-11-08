package com.team.rag.service;

import com.team.rag.bean.ChatMessage;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagQAServiceTest {

    private static final String KNOWLEDGE_MISS_NOTICE = "社团资料知识库未命中，以下回答基于通用知识，仅供参考。\n";

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
    @Mock
    private UserLongTermMemoryService userLongTermMemoryService;

    private RagProperties properties;
    private RagQAService ragQAService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        properties = new RagProperties();
        // 这些既有链路测试保持“仅改写查询”基线；融合行为由 QueryFusionServiceTest 单独覆盖。
        properties.getQueryFusion().setEnabled(false);
        ragQAService = new RagQAService(
                shortTermMemoryService,
                queryRewriteService,
                longTermMemoryService,
                bm25Retriever,
                new QueryFusionService(bm25Retriever, properties),
                new PromptBuilder(),
                dashScopeClient,
                properties,
                new AccessControlService(properties), new RerankService(dashScopeClient, properties), userLongTermMemoryService
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("RAG 问答 - 扩大授权召回后按重排结果构造最终上下文")
    void testQa_Rerank_UseSelectedCandidate() {
        // Given
        RagQo qo = new RagQo();
        qo.setQuery("申请活动场地");
        qo.setTopK(1);
        qo.setProject("club");
        String rewritten = "社团申请活动场地的流程";
        RetrievedSegment first = RetrievedSegment.builder().id("a").text("社团介绍").score(0.9).build();
        RetrievedSegment second = RetrievedSegment.builder().id("b").text("场地申请需提前三天提交").score(0.7).build();
        when(shortTermMemoryService.getRecentMessages("1")).thenReturn(List.of());
        when(queryRewriteService.rewrite(qo.getQuery(), List.of())).thenReturn(rewritten);
        when(longTermMemoryService.recall(eq(rewritten), anyMap())).thenReturn(List.of());
        Map<String, Object> filter = Map.of("status", Map.of("$eq", "ACTIVE"), "project", Map.of("$eq", "club"));
        when(bm25Retriever.retrieve(rewritten, 12, properties.getPinecone().getNamespace(), filter))
                .thenReturn(List.of(first, second));
        when(dashScopeClient.rerank(rewritten, List.of(first.getText(), second.getText()), 1))
                .thenReturn(List.of(new DashScopeClient.RerankResult(1, 0.99)));
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        when(dashScopeClient.stream(prompt.capture())).thenReturn(Flux.just("提前三天"));
        // When
        StepVerifier.create(ragQAService.qa(qo)).expectNext("提前三天").verifyComplete();
        // Then
        assertThat(prompt.getValue()).contains(second.getText()).doesNotContain(first.getText());
        var order = org.mockito.Mockito.inOrder(bm25Retriever, dashScopeClient);
        order.verify(bm25Retriever).retrieve(rewritten, 12, properties.getPinecone().getNamespace(), filter);
        order.verify(dashScopeClient).rerank(rewritten, List.of(first.getText(), second.getText()), 1);
        order.verify(dashScopeClient).stream(anyString());
    }

    @Test
    @DisplayName("RAG 问答 - 多轮追问使用改写后的查询进行召回")
    void testQa_FollowUpQuestion_UseRewrittenQueryForRetrieval() {
        // Given
        RagQo qo = new RagQo();
        qo.setQuery("那在项目里怎么处理");
        qo.setConversationId("demo");
        qo.setTopK(3);
        List<ChatMessage> memory = List.of(
                new ChatMessage("user", "Redis 缓存穿透是什么"),
                new ChatMessage("assistant", "可以通过缓存空值和布隆过滤器处理")
        );
        String rewrittenQuery = "Redis 缓存穿透在 Spring Boot 项目中如何处理";
        RetrievedSegment segment = RetrievedSegment.builder()
                .id("doc-redis")
                .score(0.91)
                .text("项目中通过缓存空值、布隆过滤器和参数校验降低缓存穿透风险。")
                .metadata(Map.of("filename", "redis-guide.md"))
                .build();
        when(shortTermMemoryService.getRecentMessages("demo")).thenReturn(memory);
        when(queryRewriteService.rewrite(qo.getQuery(), memory)).thenReturn(rewrittenQuery);
        when(longTermMemoryService.recall(eq(rewrittenQuery), anyMap())).thenReturn(List.of());
        when(bm25Retriever.retrieve(eq(rewrittenQuery), eq(12), eq(properties.getPinecone().getNamespace()), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(List.of(segment));
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(dashScopeClient.stream(promptCaptor.capture())).thenReturn(Flux.just("可以缓存空值并使用布隆过滤器。"));

        // When & Then
        StepVerifier.create(ragQAService.qa(qo))
                .expectNext("可以缓存空值并使用布隆过滤器。")
                .verifyComplete();

        assertThat(promptCaptor.getValue())
                .contains("检索用问题：" + rewrittenQuery)
                .contains("用户问题：那在项目里怎么处理")
                .contains("项目中通过缓存空值、布隆过滤器和参数校验降低缓存穿透风险。");
        verify(longTermMemoryService).recall(eq(rewrittenQuery), anyMap());
        verify(bm25Retriever).retrieve(eq(rewrittenQuery), eq(12), eq(properties.getPinecone().getNamespace()), org.mockito.ArgumentMatchers.anyMap());
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
        when(bm25Retriever.retrieve(eq(qo.getQuery()), eq(12), eq(properties.getPinecone().getNamespace()), org.mockito.ArgumentMatchers.anyMap())).thenReturn(List.of());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(dashScopeClient.stream(promptCaptor.capture())).thenReturn(Flux.just("可以缓存空值、使用布隆过滤器或做参数校验。"));

        // When & Then
        StepVerifier.create(ragQAService.qa(qo))
                .expectNext(KNOWLEDGE_MISS_NOTICE)
                .expectNext("可以缓存空值、使用布隆过滤器或做参数校验。")
                .verifyComplete();

        assertThat(promptCaptor.getValue())
                .contains("社团资料知识库未命中，以下回答基于通用知识，仅供参考")
                .contains("不要编造")
                .contains("用户问题：Redis 缓存穿透如何解决")
                .doesNotContain("以下是相关的社团资料知识库内容");
        verify(shortTermMemoryService).appendExchange(
                eq("demo"),
                eq(qo.getQuery()),
                eq(KNOWLEDGE_MISS_NOTICE + "可以缓存空值、使用布隆过滤器或做参数校验。")
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
        when(bm25Retriever.retrieve(eq(qo.getQuery()), eq(12), eq(properties.getPinecone().getNamespace()), org.mockito.ArgumentMatchers.anyMap())).thenReturn(List.of(segment));
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(dashScopeClient.stream(promptCaptor.capture())).thenReturn(Flux.just("根据知识库，缓存穿透可以通过缓存空值、布隆过滤器、参数校验解决。"));

        // When & Then
        StepVerifier.create(ragQAService.qa(qo))
                .expectNext("根据知识库，缓存穿透可以通过缓存空值、布隆过滤器、参数校验解决。")
                .verifyComplete();

        assertThat(promptCaptor.getValue())
                .contains("1、缓存穿透可以通过缓存空值、布隆过滤器、参数校验解决。")
                .contains("社团资料知识库中未找到相关答案")
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
        when(bm25Retriever.retrieve(eq(qo.getQuery()), eq(12), eq(properties.getPinecone().getNamespace()), org.mockito.ArgumentMatchers.anyMap())).thenReturn(List.of());
        when(dashScopeClient.stream(anyString())).thenReturn(Flux.just("Redis 是内存数据库。"));

        // When & Then
        StepVerifier.create(ragQAService.qa(qo))
                .expectNext(KNOWLEDGE_MISS_NOTICE)
                .expectNext("Redis 是内存数据库。")
                .verifyComplete();

        verify(shortTermMemoryService).appendExchange(
                eq("1"),
                eq(qo.getQuery()),
                eq(KNOWLEDGE_MISS_NOTICE + "Redis 是内存数据库。")
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
        when(bm25Retriever.retrieve(eq(qo.getQuery()), eq(12), eq(properties.getPinecone().getNamespace()), filterCaptor.capture()))
                .thenReturn(List.of());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(dashScopeClient.stream(promptCaptor.capture())).thenReturn(Flux.just("Redis 是内存数据库。"));

        // When & Then
        StepVerifier.create(ragQAService.qa(qo))
                .expectNext(KNOWLEDGE_MISS_NOTICE)
                .expectNext("Redis 是内存数据库。")
                .verifyComplete();

        assertThat(filterCaptor.getValue())
                .containsEntry("status", Map.of("$eq", "ACTIVE"))
                .containsEntry("project", Map.of("$eq", "infra"));
        assertThat(promptCaptor.getValue())
                .contains("社团资料知识库未命中，以下回答基于通用知识，仅供参考")
                .contains("用户问题：Redis 是什么");
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
        when(shortTermMemoryService.getRecentMessages(new AccessControlService(properties).sessionScope("1", null))).thenReturn(List.of());
        when(queryRewriteService.rewrite(qo.getQuery(), List.of())).thenReturn(qo.getQuery());
        ArgumentCaptor<Map<String, Object>> documentFilterCaptor = ArgumentCaptor.forClass(Map.class);
        when(bm25Retriever.retrieve(eq(qo.getQuery()), eq(12), eq(properties.getPinecone().getNamespace()), documentFilterCaptor.capture()))
                .thenReturn(List.of());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(dashScopeClient.stream(promptCaptor.capture())).thenReturn(Flux.just("Redis 是内存数据库。"));

        // When & Then
        StepVerifier.create(ragQAService.qa(qo))
                .expectNext(KNOWLEDGE_MISS_NOTICE)
                .expectNext("Redis 是内存数据库。")
                .verifyComplete();

        org.mockito.Mockito.verifyNoInteractions(longTermMemoryService);
        assertThat(documentFilterCaptor.getValue())
                .containsEntry("status", Map.of("$eq", "ACTIVE"))
                .containsEntry("project", Map.of("$in", List.of("infra", "docs")));
        assertThat(promptCaptor.getValue())
                .contains("社团资料知识库未命中，以下回答基于通用知识，仅供参考")
                .contains("用户问题：Redis 是什么");
    }
}
