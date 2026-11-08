package com.team.rag.service;

import com.team.rag.bean.*;
import com.team.rag.config.RagProperties;
import com.team.rag.repository.DocumentLifecycleRepository;
import com.team.rag.security.AccessControlService;
import com.team.rag.util.AdaptiveChunker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** 在精排后恢复长度兜底子块的上下文；只读取相同活动版本、相同父块的正文。 */
@Service
@RequiredArgsConstructor
public class ChunkContextExpander {
    private final DocumentLifecycleRepository repository;
    private final AccessControlService access;
    private final RagProperties properties;

    public List<RetrievedSegment> expand(List<RetrievedSegment> hits) {
        List<RetrievedSegment> result = new ArrayList<>();
        Set<String> used = new HashSet<>();
        int remaining = Math.max(1, properties.getSemanticSplitter().getContextBudgetTokens());
        for (RetrievedSegment hit : hits) {
            if (used.contains(hit.getId())) continue;
            List<StoredDocumentChunk> siblings = List.of();
            Map<String,Object> m = hit.getMetadata();
            if (m != null && "HARD_LIMIT".equals(m.get("splitReason")) && m.get("version") != null) {
                String documentId = String.valueOf(m.get("documentId"));
                int version = Integer.parseInt(String.valueOf(m.get("version")));
                RagDocument document = repository.findDocument(documentId).orElse(null);
                DocumentVersion storedVersion = repository.findVersion(documentId,version).orElse(null);
                if (document != null && document.getStatus() == DocumentStatus.ACTIVE
                        && storedVersion != null && storedVersion.getStatus() == DocumentStatus.ACTIVE
                        && access.canAccessProject(document.getProject())) {
                    siblings = repository.findChunks(documentId,version).stream()
                        .filter(c -> c.getMetadata()!=null && Objects.equals(m.get("parentId"),c.getMetadata().get("parentId")))
                        .sorted(Comparator.comparingInt(c -> number(c.getMetadata(),"startOffset"))).toList();
                }
            }
            String text = hit.getText();
            List<StoredDocumentChunk> selected = siblings;
            if (!siblings.isEmpty()) {
                String full = merge(siblings);
                if (AdaptiveChunker.tokens(full) <= remaining) text = full;
                else {
                    int position = number(m,"position");
                    selected = siblings.stream().filter(c -> Math.abs(number(c.getMetadata(),"position")-position)<=1).toList();
                    String local = merge(selected);
                    if (AdaptiveChunker.tokens(local)<=remaining) text=local; else selected=List.of();
                }
            }
            int cost = AdaptiveChunker.tokens(text);
            if (cost>remaining) continue;
            remaining-=cost;
            used.add(hit.getId()); selected.forEach(c -> used.add(c.getVectorId()));
            result.add(RetrievedSegment.builder().id(hit.getId()).text(text).score(hit.getScore())
                    .rerankScore(hit.getRerankScore()).metadata(hit.getMetadata()).build());
        }
        return result;
    }
    private static int number(Map<String,Object> m,String key) { return ((Number)m.get(key)).intValue(); }
    /** 按原文偏移跳过已覆盖部分，避免 overlap 在模型上下文重复。 */
    private static String merge(List<StoredDocumentChunk> chunks) {
        StringBuilder text=new StringBuilder(); int end=-1;
        for (StoredDocumentChunk c:chunks) {
            int start=number(c.getMetadata(),"startOffset");
            int skip=Math.max(0,end-start);
            if(skip<c.getText().length()) text.append(c.getText().substring(skip));
            end=Math.max(end,number(c.getMetadata(),"endOffset"));
        }
        return text.toString();
    }
}
