package com.team.rag.util;

import com.team.rag.bean.DocumentSegment;
import com.team.rag.config.RagProperties;
import dev.langchain4j.model.openai.OpenAiTokenizer;
import java.util.*;
import java.util.regex.*;

/** 结构优先切块；所有偏移都基于换行标准化后的 UTF-16 文本，便于无损恢复父块。 */
public class AdaptiveChunker {
    private static final OpenAiTokenizer TOKENS = new OpenAiTokenizer("text-embedding-3-small");
    private static final Pattern HEADING = Pattern.compile("(?m)^(?:#{1,6}\\s+.+|第[一二三四五六七八九十百千0-9]+[章节篇部编].*|\\d+(?:\\.\\d+)*[、.．]\\s+.+)$");
    private final SemanticSplitter semantic;
    private final RagProperties properties;
    public AdaptiveChunker(SemanticSplitter semantic, RagProperties properties) {
        this.semantic = semantic;
        this.properties = properties;
    }
    public static int tokens(String text) { return TOKENS.estimateTokenCountInText(text); }

    public List<DocumentSegment> split(String documentId, String raw, Map<String,Object> base) {
        if (raw == null || raw.isBlank()) return List.of();
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        int limit = Math.max(1, properties.getSemanticSplitter().getMaxTokens());
        String docType = String.valueOf(base.getOrDefault("docType", "")).toLowerCase(Locale.ROOT);
        boolean faq = docType.matches(".*(faq|问答|常见问题).*");
        boolean policy = docType.matches(".*(制度|章程|规范|办法|规定).*");
        if (faq) limit = Math.min(limit, 200);
        SortedSet<Integer> points = new TreeSet<>(List.of(0));
        // 表格与普通段落隔离，FAQ 以问题开头为边界，制度以段落为边界。
        Matcher lines = Pattern.compile("(?m)^.*(?:\\n|$)").matcher(text);
        boolean previousTable = false;
        while (lines.find()) {
            String line = lines.group().strip();
            boolean table = line.startsWith("|") && line.endsWith("|");
            if (table != previousTable || line.indexOf('\f') >= 0
                    || (faq && line.matches("(?i)^(?:Q[：:]|问[：:]|问题[：:]|#{1,6}\\s+).*"))
                    || (policy && line.isEmpty())) points.add(lines.start());
            previousTable = table;
        }
        List<Integer> boundaries;
        Matcher headings = HEADING.matcher(text);
        while (headings.find()) if (headings.start() > 0) points.add(headings.start());
        points.add(text.length());
        boundaries = new ArrayList<>(points);
        TreeMap<Integer,String> headingStack = new TreeMap<>();
        List<DocumentSegment> result = new ArrayList<>();
        for (int section = 0; section < boundaries.size()-1; section++) {
            int start = boundaries.get(section), end = boundaries.get(section+1);
            String content = text.substring(start,end);
            String first = content.lines().filter(l -> !l.isBlank()).findFirst().orElse("");
            Matcher markdown = Pattern.compile("^(#{1,6})\\s+(.+)$").matcher(first);
            if (markdown.matches()) {
                int level=markdown.group(1).length();
                headingStack.tailMap(level).clear(); headingStack.put(level,markdown.group(2));
            } else if (HEADING.matcher(first).matches()) {
                headingStack.clear(); headingStack.put(1,first);
            }
            String title = String.join(" > ",headingStack.values());
            boolean table = first.strip().startsWith("|");
            String chunkType = table ? "TABLE" : faq ? "FAQ" : policy ? "POLICY" : "TEXT";
            if (content.isBlank()) continue;
            List<String> blocks;
            if (table || faq || policy || tokens(content) <= limit) blocks = List.of(content);
            else {
                List<String> units = Arrays.asList(content.split("(?<=。)|(?<=！)|(?<=？)|(?<=\\n\\n)"));
                // 超长自然单元先截取预算内前缀用于边界判断，正文仍完整保留供长度兜底。
                List<String> probes = units.stream().map(u -> u.substring(0, Math.min(u.length(), 300))).toList();
                List<float[]> vectors = probes.size() > 1 ? semantic.embedUnits(probes) : List.of();
                blocks = new ArrayList<>();
                StringBuilder current = new StringBuilder(units.get(0));
                for (int n=1;n<units.size();n++) {
                    if (semantic.similarity(vectors.get(n-1),vectors.get(n)) < properties.getSemanticSplitter().getSimilarityThreshold()) {
                        blocks.add(current.toString()); current.setLength(0);
                    }
                    current.append(units.get(n));
                }
                blocks.add(current.toString());
            }
            int cursor = start;
            for (String block : blocks) {
                int blockStart = cursor;
                cursor += block.length();
                String parentId = "parent:"+documentId+":"+base.getOrDefault("version",1)+":"+blockStart;
                int offset = 0, position = 0;
                while (offset < block.length()) {
                    int finish = block.length();
                    if (tokens(block.substring(offset)) > limit) {
                        int low = offset + 1, high = block.length();
                        while (low < high) {
                            int mid = (low + high + 1) >>> 1;
                            if (tokens(block.substring(offset,mid)) <= limit) low = mid; else high = mid-1;
                        }
                        finish = low;
                        if (finish < block.length() && Character.isHighSurrogate(block.charAt(finish-1))) finish--;
                        if (finish <= offset) throw new IllegalArgumentException("token 上限不足以容纳一个 Unicode 字符");
                    }
                    String child = block.substring(offset,finish);
                    Map<String,Object> meta = new LinkedHashMap<>(base);
                    int index = result.size();
                    String id = "doc:"+documentId+":"+base.getOrDefault("version",1)+":"+index;
                    meta.put("documentId",documentId); meta.put("vectorId",id); meta.put("segmentIndex",index);
                    meta.put("parentId",parentId); meta.put("position",position++);
                    meta.put("startOffset",blockStart+offset); meta.put("endOffset",blockStart+finish);
                    meta.put("parentStartOffset",blockStart); meta.put("parentEndOffset",cursor);
                    meta.put("splitReason",tokens(block)>limit ? "HARD_LIMIT" : blocks.size()==1 ? "STRUCTURAL" : "SEMANTIC");
                    meta.put("chunkType",chunkType);
                    meta.put("titlePath",new ArrayList<>(headingStack.values()));
                    int page=0;
                    for (int c=0;c<blockStart+offset;c++) if(text.charAt(c)=='\f') page++;
                    if (page>0) meta.put("pageNumber",page);
                    meta.put("tokenCount",tokens(child)); meta.put("headingPath",title); meta.put("text",child);
                    result.add(DocumentSegment.builder().id(id).documentId(documentId).index(index).text(child).metadata(meta).build());
                    if (finish == block.length()) break;
                    int next = finish;
                    int overlap = Math.min(Math.max(0,properties.getSemanticSplitter().getOverlapTokens()),limit/2);
                    while (next > offset+1 && tokens(block.substring(next-1,finish)) <= overlap) next--;
                    if (next < block.length() && Character.isLowSurrogate(block.charAt(next))) next++;
                    offset = Math.max(offset+1,next);
                }
            }
        }
        return result;
    }
}
