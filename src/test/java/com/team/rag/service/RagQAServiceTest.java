package com.team.rag.service;

import com.team.rag.bean.RagQo;
import com.team.rag.bean.RetrievedSegment;
import com.team.rag.client.DashScopeClient;
import com.team.rag.config.RagProperties;
import com.team.rag.util.BM25Retriever;
import com.team.rag.util.PromptBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
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
        properties = new RagProperties();
        properties.getQa().setGeneralFallbackPrefix("[知识库未命中]\n");
        ragQAService = new RagQAService(
                shortTermMemoryService,
                queryRewriteService,
                longTermMemoryService,
                bm25Retriever,
                new PromptBuilder(),
                dashScopeClient,
                properties
        );
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
        when(longTermMemoryService.recall(qo.getQuery())).thenReturn(List.of());
        when(bm25Retriever.retrieve(qo.getQuery(), null, properties.getPinecone().getNamespace())).thenReturn(List.of());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(dashScopeClient.stream(promptCaptor.capture())).thenReturn(Flux.just("可以缓存空值、使用布隆过滤器或做参数校验。"));

        // When & Then
        StepVerifier.create(ragQAService.qa(qo))
                .expectNext("[知识库未命中]\n")
                .expectNext("可以缓存空值、使用布隆过滤器或做参数校验。")
                .verifyComplete();

        assertThat(promptCaptor.getValue())
                .contains("通用 AI 助手")
                .doesNotContain("RAG 检索资料");
        verify(shortTermMemoryService).appendExchange(
                eq("demo"),
                eq(qo.getQuery()),
                eq("[知识库未命中]\n可以缓存空值、使用布隆过滤器或做参数校验。")
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
        when(longTermMemoryService.recall(qo.getQuery())).thenReturn(List.of());
        when(bm25Retriever.retrieve(qo.getQuery(), null, properties.getPinecone().getNamespace())).thenReturn(List.of(segment));
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(dashScopeClient.stream(promptCaptor.capture())).thenReturn(Flux.just("根据知识库，缓存穿透可以通过缓存空值、布隆过滤器、参数校验解决。"));

        // When & Then
        StepVerifier.create(ragQAService.qa(qo))
                .expectNext("根据知识库，缓存穿透可以通过缓存空值、布隆过滤器、参数校验解决。")
                .verifyComplete();

        assertThat(promptCaptor.getValue())
                .contains("RAG 检索资料")
                .contains("redis.md")
                .doesNotContain("通用 AI 助手");
        verify(shortTermMemoryService).appendExchange(
                eq("demo"),
                eq(qo.getQuery()),
                eq("根据知识库，缓存穿透可以通过缓存空值、布隆过滤器、参数校验解决。")
        );
    }
}
