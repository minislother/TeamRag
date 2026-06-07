package com.team.rag.frontend;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("前端页面 Playwright 冒烟集成测试")
class FrontendSmokeIntegrationTest {

    private static final Path STATIC_ROOT = Path.of("src/main/resources/static").toAbsolutePath().normalize();

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/auth/me", this::authMe);
        server.createContext("/api/document/jobs", this::documentJob);
        server.createContext("/api/document/upload", this::documentUpload);
        server.createContext("/api/document", this::documentList);
        server.createContext("/api/rag/qa", this::ragQa);
        server.createContext("/", this::staticResource);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("聊天页面 - 加载文档并完成一次问答")
    void testChatPage_LoadDocumentsAndAskQuestion() {
        Path browserExecutable = browserExecutable();
        Assumptions.assumeTrue(browserExecutable != null,
                "未找到本机 Chrome/Edge，跳过前端冒烟测试；可安装浏览器后重新运行 integration-tests profile");
        try (Playwright playwright = Playwright.create(); Browser browser = launchBrowser(playwright, browserExecutable)) {
            Page page = browser.newPage();
            List<String> consoleErrors = new ArrayList<>();
            page.onConsoleMessage(message -> {
                if ("error".equals(message.type())) {
                    consoleErrors.add(message.text());
                }
            });

            page.navigate(baseUrl);
            page.locator("aside h1").waitFor();
            assertThat(page.locator("aside h1").textContent()).isEqualTo("团队知识库");
            page.locator("text=redis-guide.md").waitFor();
            assertThat(page.locator("#runtimeInfo").textContent()).contains("text-embedding-v3", "glm-5");

            page.fill("#questionInput", "Redis 缓存穿透如何解决");
            page.click("#sendButton");
            page.locator("text=已基于知识库回答").waitFor();

            page.locator("text=上传新版本").first().click();
            assertThat(page.locator("#updateTarget").isVisible()).isTrue();
            assertThat(page.locator("#updateTargetText").textContent()).contains("redis-guide.md");
            assertThat(consoleErrors).isEmpty();
        }
    }

    private Browser launchBrowser(Playwright playwright, Path browserExecutable) {
        try {
            return playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setExecutablePath(browserExecutable));
        } catch (RuntimeException ex) {
            Assumptions.abort("Playwright 无法启动本机浏览器，跳过前端冒烟测试: " + ex.getMessage());
            throw ex;
        }
    }

    private Path browserExecutable() {
        String configured = System.getenv("PLAYWRIGHT_CHROMIUM_EXECUTABLE");
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        return List.of(
                        Path.of("C:/Program Files/Google/Chrome/Application/chrome.exe"),
                        Path.of("C:/Program Files (x86)/Google/Chrome/Application/chrome.exe"),
                        Path.of("C:/Program Files/Microsoft/Edge/Application/msedge.exe"),
                        Path.of("C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe")
                ).stream()
                .filter(Files::isRegularFile)
                .findFirst()
                .orElse(null);
    }

    private void authMe(HttpExchange exchange) throws IOException {
        sendJson(exchange, """
                {"securityEnabled":false,"authenticated":false,"username":"local","role":"ADMIN","projects":["*"]}
                """);
    }

    private void documentList(HttpExchange exchange) throws IOException {
        sendJson(exchange, """
                [
                  {
                    "documentId":"doc-1",
                    "filename":"redis-guide.md",
                    "docType":"研发规范",
                    "project":"infra",
                    "tags":"Redis",
                    "currentVersion":2,
                    "status":"ACTIVE",
                    "versions":[],
                    "jobs":[]
                  }
                ]
                """);
    }

    private void documentUpload(HttpExchange exchange) throws IOException {
        sendJson(exchange, """
                {"documentId":"doc-1","jobId":"job-ui","version":3,"filename":"redis-guide.md","status":"PENDING","message":"文档已提交入库任务"}
                """);
    }

    private void documentJob(HttpExchange exchange) throws IOException {
        sendJson(exchange, """
                {"jobId":"job-ui","documentId":"doc-1","version":3,"status":"SUCCESS","progress":100,"errorMessage":"","retryCount":0}
                """);
    }

    private void ragQa(HttpExchange exchange) throws IOException {
        send(exchange, 200, "text/event-stream;charset=UTF-8", """
                data: 已基于知识库回答：可以使用空值缓存、布隆过滤器和参数校验。

                data: [DONE]

                """);
    }

    private void staticResource(HttpExchange exchange) throws IOException {
        String requestPath = exchange.getRequestURI().getPath();
        String staticPath = "/".equals(requestPath) ? "/index.html" : requestPath;
        Path file = STATIC_ROOT.resolve(staticPath.substring(1)).normalize();
        if (!file.startsWith(STATIC_ROOT) || !Files.exists(file) || Files.isDirectory(file)) {
            send(exchange, 404, "text/plain;charset=UTF-8", "Not found");
            return;
        }
        send(exchange, 200, contentType(file), Files.readAllBytes(file));
    }

    private void sendJson(HttpExchange exchange, String body) throws IOException {
        send(exchange, 200, "application/json;charset=UTF-8", body);
    }

    private void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        send(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (var outputStream = exchange.getResponseBody()) {
            outputStream.write(body);
        } finally {
            exchange.close();
        }
    }

    private String contentType(Path file) {
        String name = file.getFileName().toString();
        if (name.endsWith(".html")) {
            return "text/html;charset=UTF-8";
        }
        if (name.endsWith(".css")) {
            return "text/css;charset=UTF-8";
        }
        if (name.endsWith(".js")) {
            return "application/javascript;charset=UTF-8";
        }
        if (name.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }
}
