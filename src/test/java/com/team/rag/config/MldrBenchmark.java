package com.team.rag.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.bean.DocumentSegment;
import com.team.rag.bean.RetrievedSegment;
import com.team.rag.bean.SparseVector;
import com.team.rag.client.DashScopeClient;
import com.team.rag.client.PineconeClient;
import com.team.rag.service.RerankService;
import com.team.rag.util.BM25Retriever;
import com.team.rag.util.BM25SparseEncoder;
import com.team.rag.util.RagEvaluationMetrics;
import com.team.rag.util.SemanticSplitter;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.ResourcePropertySource;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/**
 * 显式运行的 MLDR dev 基准，不由 mvn test 自动调用付费模型。
 * 真实复用分块/embedding/客户端 hybrid fallback/重排；向量库由内存精确余弦搜索替代。
 * check 仅检查真实基础切分，不把它当成语义合并、检索或重排质量结果。
 */
public class MldrBenchmark {
    private static final Path ROOT = Path.of("target/mldr");
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        // SDK 异常可能含业务正文；基准只记录错误类型及已知账号状态。
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("ROOT"))
                .setLevel(ch.qos.logback.classic.Level.OFF);
        String mode = args.length == 0 ? "check" : args[0];
        if (!Set.of("check", "run").contains(mode)) throw new IllegalArgumentException("Use check or run");
        RagProperties properties = properties();
        List<JsonNode> documents = readLines("documents.jsonl");
        List<JsonNode> queries = readLines("queries.jsonl");
        checkBaseSplitting(documents, properties);
        checkMergeBounds(documents, properties);
        if (mode.equals("check")) return;

        CachedClient client = new CachedClient(properties);
        // 先检查两种真实接口，任何一个失败都停止；不将降级结果冒充模型重排结果。
        try {
            client.embed(List.of("社团申请活动场地"));
            client.rerank("申请场地", List.of("场地申请需要提前三天", "天气晴朗"), 2);
        } catch (RuntimeException ex) {
            String status = failureCode(ex);
            write("run-status.json", Map.of("status", "BLOCKED", "stage", "live_model_preflight",
                    "reason", status, "metrics", "NOT_RUN", "time", java.time.Instant.now().toString()));
            System.out.println("MLDR BLOCKED: " + status + "; no retrieval/rerank metrics generated.");
            System.exit(2);
        }
        try {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("manifest", JSON.readTree(ROOT.resolve("manifest.json").toFile()));
            report.put("backend", "In-memory exact cosine -> production BM25Retriever sparse fallback -> live rerank");
            report.put("embeddingModel", properties.getDashscope().getEmbeddingModel());
            report.put("dimensions", properties.getDashscope().getEmbeddingDimensions());
            report.put("splitter", properties.getSemanticSplitter());
            report.put("alpha", properties.getHybrid().getAlpha());
            report.put("minScore", properties.getHybrid().getMinScore());
            report.put("rerankModel", properties.getRerank().getModel());
            report.put("scope", "Fixed dev subset; no Pinecone, query rewriting, memory or answer generation");
            properties.getRerank().setEnabled(true);
            properties.getRerank().setCandidateCount(12);
            for (String variant : List.of("fixed", "semantic")) {
                report.put(variant, evaluate(variant, documents, queries, properties, client));
                write("metrics.json", report);
            }
            write("run-status.json", Map.of("status", "COMPLETE", "time", java.time.Instant.now().toString()));
            System.out.println("MLDR COMPLETE: target/mldr/metrics.json");
        } catch (Exception ex) {
            write("run-status.json", Map.of("status", "FAILED", "reason", failureCode(ex),
                    "time", java.time.Instant.now().toString()));
            System.out.println("MLDR FAILED: " + failureCode(ex) + "; embedding cache retained for restart.");
            System.exit(3);
        }
    }

    private static RagProperties properties() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new ResourcePropertySource("classpath:application.properties"));
        if (Files.exists(Path.of(".env"))) environment.getPropertySources().addFirst(
                new MapPropertySource("benchmark-dotenv", DotenvApplicationListener.parseEnv(Path.of(".env"))));
        return Binder.get(environment).bind("rag", RagProperties.class).orElseGet(RagProperties::new);
    }

    /** 反射只用于离线检查生产私有基础切分阶段，不复制另一份切分算法。 */
    private static void checkBaseSplitting(List<JsonNode> documents, RagProperties properties) throws Exception {
        var split = SemanticSplitter.class.getDeclaredMethod("splitToBaseSegments", String.class);
        split.setAccessible(true);
        SemanticSplitter splitter = new SemanticSplitter(null, properties);
        List<Integer> lengths = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        int empty = 0, oversized = 0, brokenSurrogates = 0;
        int limit = Math.max(properties.getSemanticSplitter().getMaxChars() / 3, 300);
        for (JsonNode document : documents) {
            String original = document.path("text").asText();
            @SuppressWarnings("unchecked") List<String> parts = (List<String>) split.invoke(splitter, original);
            if (!compact(original).equals(compact(String.join("", parts)))) changed.add(document.path("id").asText());
            for (String part : parts) {
                lengths.add(part.length());
                if (part.isBlank()) empty++;
                if (part.length() > limit) oversized++;
                if (!part.isEmpty() && (Character.isLowSurrogate(part.charAt(0))
                        || Character.isHighSurrogate(part.charAt(part.length() - 1)))) brokenSurrogates++;
            }
        }
        Collections.sort(lengths);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", "BASE splitting only; no embeddings, semantic merging, overlap, retrieval or reranking");
        result.put("documents", documents.size());
        result.put("baseChunks", lengths.size());
        result.put("limitUtf16Chars", limit);
        result.put("maxUtf16Chars", lengths.get(lengths.size() - 1));
        result.put("p50Utf16Chars", lengths.get(lengths.size() / 2));
        result.put("p95Utf16Chars", lengths.get((int) (lengths.size() * 0.95)));
        result.put("emptyChunks", empty);
        result.put("oversizedChunks", oversized);
        result.put("brokenSurrogateBoundaries", brokenSurrogates);
        result.put("nonWhitespaceContentOrOrderChangedDocuments", changed);
        write("base-splitting-check.json", result);
        System.out.println(JSON.writeValueAsString(result));
    }

    private static String compact(String text) { return text.replaceAll("[\\s\\p{Z}]+", ""); }

    /** 两个合并分支的结构压力测试：向量为人工边界输入，结果不能解释为语义质量。 */
    private static void checkMergeBounds(List<JsonNode> documents, RagProperties properties) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", "Synthetic vectors exercise merge/overlap length invariants on real MLDR text; NOT semantic quality");
        for (boolean merge : List.of(true, false)) {
            DashScopeClient boundaryClient = new DashScopeClient(properties, null) {
                @Override public List<float[]> embed(List<String> texts) {
                    List<float[]> result = new ArrayList<>();
                    for (int i = 0; i < texts.size(); i++) result.add(merge || i % 2 == 0 ? new float[]{1, 0} : new float[]{0, 1});
                    return result;
                }
            };
            SemanticSplitter splitter = new SemanticSplitter(boundaryClient, properties);
            int chunks = 0, oversized = 0, max = 0;
            List<String> examples = new ArrayList<>();
            for (JsonNode doc : documents) {
                for (DocumentSegment chunk : splitter.split(doc.path("id").asText(), doc.path("text").asText(), Map.of())) {
                    chunks++; max = Math.max(max, chunk.getText().length());
                    if (chunk.getText().length() > properties.getSemanticSplitter().getMaxChars()) {
                        oversized++;
                        if (examples.size() < 5) examples.add(chunk.getId());
                    }
                }
            }
            result.put(merge ? "allAdjacentSimilar" : "allAdjacentDissimilar",
                    Map.of("chunks", chunks, "maxUtf16Chars", max, "oversizedChunks", oversized, "exampleChunkIds", examples));
        }
        write("merge-boundary-check.json", result);
        System.out.println(JSON.writeValueAsString(result));
    }

    private static Map<String, Object> evaluate(String variant, List<JsonNode> documents, List<JsonNode> queries,
                                               RagProperties properties, CachedClient client) throws Exception {
        List<IndexedChunk> index = new ArrayList<>();
        List<Integer> lengths = new ArrayList<>();
        SemanticSplitter splitter = new SemanticSplitter(client, properties);
        int completed = 0;
        for (JsonNode doc : documents) {
            String id = doc.path("id").asText(), text = doc.path("text").asText();
            List<DocumentSegment> chunks = variant.equals("semantic")
                    ? splitter.split(id, text, Map.of("status", "ACTIVE", "project", "mldr-dev"))
                    : fixed(id, text, properties);
            List<float[]> vectors = client.embed(chunks.stream().map(DocumentSegment::getText).toList());
            for (int i = 0; i < chunks.size(); i++) {
                DocumentSegment chunk = chunks.get(i);
                lengths.add(chunk.getText().length());
                index.add(new IndexedChunk(chunk, normalize(vectors.get(i))));
            }
            if (++completed % 25 == 0) System.out.printf("%s index %d/%d documents, %d chunks%n", variant, completed, documents.size(), index.size());
        }
        BM25Retriever retriever = new BM25Retriever(new BM25SparseEncoder(), client,
                new ExactVectorStore(index, properties), properties);
        RerankService rerank = new RerankService(client, properties);
        List<Map<String, Object>> results = new ArrayList<>();
        int degraded = 0;
        for (JsonNode query : queries) {
            String text = query.path("text").asText();
            List<String> relevant = new ArrayList<>();
            query.path("relevant").forEach(item -> relevant.add(item.asText()));
            List<RetrievedSegment> candidates = retriever.retrieve(text, 12, "mldr-dev",
                    Map.of("status", Map.of("$eq", "ACTIVE"), "project", Map.of("$eq", "mldr-dev")));
            long started = System.nanoTime();
            List<RetrievedSegment> ranked = rerank.rerank(text, candidates, 6);
            double milliseconds = (System.nanoTime() - started) / 1_000_000.0;
            boolean fallback = candidates.size() > 1 && ranked.stream().anyMatch(segment -> segment.getRerankScore() == null);
            if (fallback) degraded++;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", query.path("id").asText());
            row.put("candidate12", metrics(relevant, candidates, 12));
            row.put("hybrid6", metrics(relevant, candidates.stream().limit(6).toList(), 6));
            row.put("rerank6", metrics(relevant, ranked, 6));
            row.put("rerankDegraded", fallback);
            row.put("rerankMs", milliseconds);
            row.put("candidateIds", candidates.stream().map(RetrievedSegment::getId).toList());
            row.put("rerankedIds", ranked.stream().map(RetrievedSegment::getId).toList());
            results.add(row);
            write(variant + "-queries.json", results);
            if (results.size() % 10 == 0) System.out.printf("%s queries %d/%d, degraded=%d%n", variant, results.size(), queries.size(), degraded);
        }
        Collections.sort(lengths);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("queries", results.size()); summary.put("chunks", index.size());
        summary.put("chunkMax", lengths.get(lengths.size() - 1));
        summary.put("chunkP50", lengths.get(lengths.size() / 2));
        summary.put("chunkP95", lengths.get((int) (lengths.size() * 0.95)));
        summary.put("chunksAboveMaxChars", lengths.stream().filter(n -> n > properties.getSemanticSplitter().getMaxChars()).count());
        summary.put("rerankDegraded", degraded);
        for (String stage : List.of("candidate12", "hybrid6", "rerank6")) {
            Map<String, Double> mean = new LinkedHashMap<>();
            for (String metric : List.of("recall", "mrr", "ndcg")) {
                mean.put(metric, results.stream().mapToDouble(row -> ((Number) ((Map<?, ?>) row.get(stage)).get(metric)).doubleValue()).average().orElseThrow());
            }
            summary.put(stage, mean);
        }
        return summary;
    }

    /** 先截取实际进入上下文的块，再按 docId 去重；不把同文档多块算成多次命中。 */
    private static Map<String, Double> metrics(List<String> relevant, List<RetrievedSegment> segments, int k) {
        Map<String, RetrievedSegment> unique = new LinkedHashMap<>();
        for (RetrievedSegment segment : segments) {
            String doc = segment.getMetadata().get("documentId").toString();
            unique.putIfAbsent(doc, RetrievedSegment.builder().id(doc).build());
        }
        var result = new RagEvaluationMetrics().evaluateRetrieval(relevant, new ArrayList<>(unique.values()), k);
        return Map.of("recall", result.getRecallAtK(), "mrr", result.getMrr(), "ndcg", result.getNdcgAtK());
    }

    private static List<DocumentSegment> fixed(String id, String text, RagProperties properties) {
        List<DocumentSegment> chunks = new ArrayList<>();
        int size = properties.getSemanticSplitter().getMaxChars();
        int step = size - properties.getSemanticSplitter().getOverlapChars();
        if (size <= 0 || step <= 0) throw new IllegalArgumentException("Invalid fixed chunk size/overlap");
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(start + size, text.length()), index = chunks.size();
            chunks.add(DocumentSegment.builder().id(id + ":" + index).documentId(id).index(index)
                    .text(text.substring(start, end)).metadata(Map.of("documentId", id, "status", "ACTIVE", "project", "mldr-dev")).build());
            if (end == text.length()) break;
        }
        return chunks;
    }

    /** 精确本地余弦只替代 Pinecone I/O；生产 BM25Retriever 的 fallback 逻辑原样运行。 */
    private static class ExactVectorStore extends PineconeClient {
        private final List<IndexedChunk> index;
        ExactVectorStore(List<IndexedChunk> index, RagProperties properties) {
            super(null, JSON, properties); this.index = index;
        }
        @Override public List<RetrievedSegment> query(String namespace, float[] dense, SparseVector sparse,
                int topK, double alpha, double minScore, Map<String, Object> filter) {
            throw new SparseQueryUnsupportedException("Offline exact cosine backend", null);
        }
        @Override public List<RetrievedSegment> queryDense(String namespace, float[] dense, int topK,
                double minScore, Map<String, Object> filter) {
            float[] query = normalize(dense);
            PriorityQueue<RetrievedSegment> best = new PriorityQueue<>(Comparator.comparingDouble(RetrievedSegment::getScore));
            for (IndexedChunk item : index) {
                double score = 0;
                for (int i = 0; i < query.length; i++) score += query[i] * item.vector[i];
                if (best.size() < topK || score > best.peek().getScore()) {
                    DocumentSegment chunk = item.chunk;
                    best.add(RetrievedSegment.builder().id(chunk.getId()).text(chunk.getText())
                            .metadata(chunk.getMetadata()).score(score).build());
                    if (best.size() > topK) best.poll();
                }
            }
            return best.stream().sorted(Comparator.comparingDouble(RetrievedSegment::getScore).reversed()).toList();
        }
    }

    /** 仅缓存真实 embedding，按模型/地址/维度/原文哈希隔离；最多十条一批防止服务批量超限。 */
    private static class CachedClient extends DashScopeClient {
        private final RagProperties properties;
        CachedClient(RagProperties properties) {
            super(properties, new RAGConfig().ragWebClient(JSON)); this.properties = properties;
        }
        @Override public List<float[]> embed(List<String> texts) {
            try {
                List<float[]> result = new ArrayList<>(Collections.nCopies(texts.size(), null));
                List<Integer> missing = new ArrayList<>();
                for (int i = 0; i < texts.size(); i++) {
                    Path file = cachePath(texts.get(i));
                    if (Files.exists(file)) {
                        try (DataInputStream input = new DataInputStream(Files.newInputStream(file))) {
                            int size = input.readInt();
                            if (size != properties.getDashscope().getEmbeddingDimensions()) throw new IllegalStateException("Invalid cached dimension");
                            float[] vector = new float[size];
                            for (int j = 0; j < size; j++) vector[j] = input.readFloat();
                            result.set(i, vector);
                        }
                    } else missing.add(i);
                }
                for (int start = 0; start < missing.size(); start += 10) {
                    List<Integer> batch = missing.subList(start, Math.min(start + 10, missing.size()));
                    List<float[]> vectors = super.embed(batch.stream().map(texts::get).toList());
                    if (vectors.size() != batch.size()) throw new IllegalStateException("Embedding count mismatch");
                    for (int j = 0; j < batch.size(); j++) {
                        int position = batch.get(j); float[] vector = vectors.get(j);
                        Path file = cachePath(texts.get(position)), temp = file.resolveSibling(file.getFileName() + ".tmp");
                        Files.createDirectories(file.getParent());
                        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(temp))) {
                            output.writeInt(vector.length);
                            for (float value : vector) output.writeFloat(value);
                        }
                        Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        result.set(position, vector);
                    }
                }
                return result;
            } catch (java.io.IOException ex) { throw new IllegalStateException("Embedding cache I/O failed", ex); }
        }
        private Path cachePath(String text) {
            String config = properties.getDashscope().getBaseUrl() + "|" + properties.getDashscope().getEmbeddingModel()
                    + "|" + properties.getDashscope().getEmbeddingDimensions() + "|" + text;
            try {
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(config.getBytes(StandardCharsets.UTF_8)));
                return ROOT.resolve("embeddings").resolve(hash.substring(0, 2)).resolve(hash + ".bin");
            } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        }
    }

    private record IndexedChunk(DocumentSegment chunk, float[] vector) {}
    private static float[] normalize(float[] input) {
        float[] result = input.clone(); double norm = 0;
        for (float value : input) { if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite embedding"); norm += value * value; }
        if (norm == 0) throw new IllegalArgumentException("Zero embedding");
        norm = Math.sqrt(norm);
        for (int i = 0; i < result.length; i++) result[i] /= (float) norm;
        return result;
    }
    private static List<JsonNode> readLines(String file) throws Exception {
        List<JsonNode> result = new ArrayList<>();
        try (var reader = Files.newBufferedReader(ROOT.resolve(file))) {
            for (String line; (line = reader.readLine()) != null;) result.add(JSON.readTree(line));
        }
        return result;
    }
    private static void write(String file, Object value) throws Exception {
        JSON.writerWithDefaultPrettyPrinter().writeValue(ROOT.resolve(file).toFile(), value);
    }
    private static String failureCode(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains("Arrearage")) return "Arrearage";
        }
        return error.getClass().getSimpleName();
    }
}
