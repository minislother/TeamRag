package com.team.rag.controller;
import com.team.rag.security.AccessControlService;
import com.team.rag.service.UserLongTermMemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;

/** 用户只能治理自己的记忆，API 不接受客户端指定 owner 或 tenant。 */
@RestController @RequestMapping("/api/memory") @RequiredArgsConstructor
public class UserMemoryController {
 private final AccessControlService access;
 private final UserLongTermMemoryService memories;
 public record WriteRequest(String category,String key,String text) {}
 public record PauseRequest(boolean paused) {}
 private String owner() {
  if(!access.securityEnabled()) throw new AccessDeniedException("请启用认证后使用个人记忆管理");
  return access.memoryOwner();
 }
 @GetMapping public Map<String,Object> list() { String owner=owner();return Map.of("paused",memories.paused(owner),"entries",memories.list(owner)); }
 @GetMapping("/history") public List<UserLongTermMemoryService.Entry> history() { return memories.history(owner()); }
 @GetMapping("/export") public List<UserLongTermMemoryService.Entry> export() { return memories.list(owner()); }
 @PutMapping public UserLongTermMemoryService.Entry put(@RequestBody WriteRequest request) { return memories.put(owner(),request.category(),request.key(),request.text()); }
 @DeleteMapping("/{id}") public void delete(@PathVariable String id) { memories.delete(owner(),id); }
 @DeleteMapping public void clear() { memories.clear(owner()); }
 @PutMapping("/pause") public void pause(@RequestBody PauseRequest request) { memories.pause(owner(),request.paused()); }
}
