package com.team.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 个人记忆独立于知识证据：显式写入、结构化更新、按用户治理；JDBC 模式写入失败不伪装成功。 */
@Service
public class UserLongTermMemoryService {
    private static final org.slf4j.Logger AUDIT=org.slf4j.LoggerFactory.getLogger(UserLongTermMemoryService.class);
    public record Entry(String id,String category,String key,String text,String source,
                        double confidence,Instant createdAt,Instant updatedAt,Instant expiresAt,int version) {}
    public static class State {
        public boolean paused;
        public List<Entry> entries=new ArrayList<>();
        public List<Entry> history=new ArrayList<>();
    }
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final Map<String,State> local=new ConcurrentHashMap<>();
    @Autowired public UserLongTermMemoryService(ObjectMapper mapper,@Autowired(required=false) JdbcTemplate jdbc) {
        this.mapper=mapper; this.jdbc=jdbc;
    }
    private State load(String owner) {
        if (jdbc==null) return local.computeIfAbsent(owner,k->new State());
        List<String> rows=jdbc.query("SELECT state_json FROM rag_memory_profiles WHERE owner_id=?",(rs,n)->rs.getString(1),owner);
        if(rows.isEmpty()) return new State();
        try { return mapper.readValue(rows.get(0),State.class); }
        catch(Exception ex) { throw new IllegalStateException("记忆数据无法读取",ex); }
    }
    private void save(String owner,State state) {
        if(jdbc==null) { local.put(owner,state); return; }
        try {
            String json=mapper.writeValueAsString(state);
            jdbc.update("INSERT INTO rag_memory_profiles(owner_id,state_json) VALUES (?,?) ON DUPLICATE KEY UPDATE state_json=VALUES(state_json)",owner,json);
        } catch(Exception ex) { throw new IllegalStateException("记忆持久化失败",ex); }
    }
    public synchronized List<Entry> list(String owner) {
        requireOwner(owner);
        return load(owner).entries.stream().filter(e->e.expiresAt().isAfter(Instant.now())).toList();
    }
    public synchronized Entry put(String owner,String category,String key,String text) {
        requireOwner(owner);
        if(!Set.of("preference","fact","episode","plan","procedure").contains(category)
                ||key==null||!key.matches("[A-Za-z0-9_.-]{1,64}")||text==null||text.isBlank()||text.length()>500)
            throw new IllegalArgumentException("记忆类型、键或内容无效");
        // 不保存明显凭证；更细的隐私判断需由产品侧审批策略进一步接入。
        if(text.matches("(?is).*(api.?key|密码|身份证|银行卡|sk-[a-z0-9]).*")) throw new IllegalArgumentException("请勿将凭证或敏感标识写入记忆");
        State state=load(owner);
        if(state.paused) throw new IllegalStateException("长期记忆已暂停");
        Entry previous=state.entries.stream().filter(e->e.key().equals(key)).findFirst().orElse(null);
        Instant now=Instant.now();
        Entry entry=new Entry(previous==null?UUID.randomUUID().toString():previous.id(),category,key,text,
                "explicit-user",1.0,previous==null?now:previous.createdAt(),now,
                now.plus(category.equals("plan")?30:365,ChronoUnit.DAYS),previous==null?1:previous.version()+1);
        if(previous!=null) state.history.add(previous);
        if(state.history.size()>100) state.history.remove(0);
        state.entries.removeIf(e->e.key().equals(key)||!e.expiresAt().isAfter(now));
        if(state.entries.size()>=100) throw new IllegalStateException("记忆已达100条，请先清理");
        state.entries.add(entry);save(owner,state);AUDIT.info("memory action=put owner={} id={} version={}",owner,entry.id(),entry.version());return entry;
    }
    public synchronized void delete(String owner,String id) {
        requireOwner(owner); State state=load(owner); state.entries.removeIf(e->e.id().equals(id));state.history.removeIf(e->e.id().equals(id));save(owner,state);AUDIT.info("memory action=delete owner={} id={}",owner,id);
    }
    public synchronized void clear(String owner) {
        requireOwner(owner); State state=load(owner);state.entries.clear();state.history.clear();save(owner,state);AUDIT.info("memory action=clear owner={}",owner);
    }
    public synchronized void pause(String owner,boolean paused) {
        requireOwner(owner);State state=load(owner);state.paused=paused;save(owner,state);AUDIT.info("memory action=pause owner={} paused={}",owner,paused);
    }
    public synchronized List<Entry> history(String owner) {
        requireOwner(owner);return List.copyOf(load(owner).history);
    }
    public synchronized boolean paused(String owner) { requireOwner(owner);return load(owner).paused; }
    public synchronized String context(String owner,String query) {
        if(owner==null||owner.isBlank()) return "";
        State state=load(owner); if(state.paused) return "";
        return state.entries.stream().filter(e->e.expiresAt().isAfter(Instant.now()))
                .filter(e->e.category().equals("preference")||e.category().equals("procedure")
                        ||query!=null&&Arrays.stream(e.text().split("[\\s，。；]+"))
                        .anyMatch(word->word.length()>1&&query.contains(word)))
                .sorted(Comparator.comparing(Entry::updatedAt).reversed()).limit(6)
                .map(e->"- ["+e.category()+"] "+e.text()).reduce("",(a,b)->a+b+"\n");
    }
    /** 仅明确的跨会话记忆请求入库；临时的“不要/必须”不再触发自动保存。 */
    public synchronized void extractAndStore(String owner,String text) {
        if(owner==null||owner.isBlank()||text==null||text.length()>500) return;
        if(text.contains("不要记住")||text.contains("别再记住")||text.contains("停止记忆")) { pause(owner,true);return; }
        if(paused(owner)) return;
        if(text.startsWith("请记住")||text.startsWith("以后请")) {
            String key=text.contains("简短")||text.contains("简洁")||text.contains("详细")?"response.style":"explicit."+Integer.toUnsignedString(text.hashCode());
            put(owner,"preference",key,text);
        }
    }
    private static void requireOwner(String owner) {
        if(owner==null||owner.isBlank()) throw new IllegalArgumentException("个人记忆需要已认证用户");
    }
}
