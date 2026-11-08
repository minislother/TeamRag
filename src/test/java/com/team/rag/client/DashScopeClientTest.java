package com.team.rag.client;

import com.team.rag.config.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@DisplayName("百炼重排协议测试")
class DashScopeClientTest {
    private final RagProperties properties = new RagProperties();

    @Test
    @DisplayName("重排请求 - 原生 HTTP JSON 协议与文档顺序正确")
    void testRerank_HttpBody_NativeProtocol() throws Exception {
        // Given - 仅启动本机假服务，不调用付费模型。
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var received = new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rerank", exchange -> {
            received.set(mapper.readTree(exchange.getRequestBody()));
            byte[] body = "{\"output\":{\"results\":[{\"index\":1,\"relevance_score\":0.95}]}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            properties.getDashscope().setApiKey("test-key");
            properties.getRerank().setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/rerank");
            // When
            var result = new DashScopeClient(properties, WebClient.create()).rerank("社团", List.of("介绍", "申请"), 1);
            // Then
            assertThat(received.get()).isEqualTo(mapper.readTree("""
                    {"model":"gte-rerank-v2","input":{"query":"社团","documents":["介绍","申请"]},
                    "parameters":{"top_n":1,"return_documents":false}}
                    """));
            assertThat(result).containsExactly(new DashScopeClient.RerankResult(1, 0.95));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("重排请求 - HTTP 错误向业务降级层传播")
    void testRerank_HttpFailure_Propagate() {
        // Given
        properties.getDashscope().setApiKey("test-key");
        WebClient webClient = WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS).build())).build();
        // When & Then
        assertThatThrownBy(() -> new DashScopeClient(properties, webClient).rerank("问题", List.of("a"), 1))
                .isInstanceOf(org.springframework.web.reactive.function.client.WebClientResponseException.TooManyRequests.class);
    }

    @Test
    @DisplayName("重排响应 - 根据下标映射并按分数降序排列")
    void testRerank_ValidResponse_Sort() {
        // Given
        properties.getDashscope().setApiKey("test-key");
        AtomicInteger calls = new AtomicInteger();
        WebClient webClient = WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet();
            assertThat(request.url().toString()).isEqualTo(properties.getRerank().getUrl());
            assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer test-key");
            return Mono.just(response("{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":0.1},{\"index\":1,\"relevance_score\":0.9}]}}"));
        }).build();
        // When
        var result = new DashScopeClient(properties, webClient).rerank("问题", List.of("a", "b"), 2);
        // Then
        assertThat(result).containsExactly(new DashScopeClient.RerankResult(1, 0.9), new DashScopeClient.RerankResult(0, 0.1));
        assertThat(calls.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"output\":{\"results\":[]}}",
            "{\"output\":{\"results\":[{\"index\":3,\"relevance_score\":0.9}]}}",
            "{\"output\":{\"results\":[{\"index\":-1,\"relevance_score\":0.9}]}}",
            "{\"output\":{\"results\":[{\"index\":0.5,\"relevance_score\":0.9}]}}",
            "{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":1.1}]}}",
            "{\"output\":{\"results\":[{\"index\":0}]}}"
    })
    @DisplayName("重排响应 - 非法结构或下标分数触发失败")
    void testRerank_InvalidResponse_Reject(String json) {
        // Given
        properties.getDashscope().setApiKey("test-key");
        WebClient webClient = WebClient.builder().exchangeFunction(request -> Mono.just(response(json))).build();
        // When & Then
        assertThatThrownBy(() -> new DashScopeClient(properties, webClient).rerank("问题", List.of("a", "b"), 1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("重排响应 - 重复下标不能重复注入上下文")
    void testRerank_DuplicateIndex_Reject() {
        // Given
        properties.getDashscope().setApiKey("test-key");
        WebClient webClient = WebClient.builder().exchangeFunction(request -> Mono.just(response(
                "{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.8}]}}"))).build();
        // When & Then
        assertThatThrownBy(() -> new DashScopeClient(properties, webClient).rerank("问题", List.of("a", "b"), 2))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("重排超时 - 在配置时间内结束等待")
    void testRerank_Timeout_Fail() {
        // Given
        properties.getDashscope().setApiKey("test-key");
        properties.getRerank().setTimeoutSeconds(1);
        WebClient webClient = WebClient.builder().exchangeFunction(request -> Mono.never()).build();
        // When & Then
        assertThatThrownBy(() -> new DashScopeClient(properties, webClient).rerank("问题", List.of("a"), 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Timeout");
    }

    private ClientResponse response(String body) {
        return ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body(body).build();
    }
}
