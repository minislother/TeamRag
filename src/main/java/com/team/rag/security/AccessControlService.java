package com.team.rag.security;

import com.team.rag.config.RagProperties;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

@Service
public class AccessControlService {

    private final RagProperties properties;

    public AccessControlService(RagProperties properties) {
        this.properties = properties;
    }

    public AuthenticatedUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (Boolean.TRUE.equals(properties.getSecurity().getEnabled())) {
            if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
                return user;
            }
            throw new AccessDeniedException("请先登录");
        }
        return new AuthenticatedUser("local", "ADMIN", List.of("*"));
    }

    /** 服务端部署租户边界；客户端不能通过 query/body/header 切换租户。 */
    public String tenantId() { return properties.getSecurity().getTenantId(); }

    public String memoryOwner() {
        if (!securityEnabled()) return ""; // 匿名演示不沉淀共享个人画像。
        return tenantId() + ":" + currentUser().username();
    }

    /** 用户、权限集合和项目共同限定工作记忆，权限收回后不复用旧摘要。 */
    public String sessionScope(String session, String project) {
        if (!securityEnabled()) return session;
        AuthenticatedUser user = currentUser();
        String acl = user.role() + ":" + user.projects().stream().sorted().toList();
        String scope = tenantId() + "\n" + user.username() + "\n" + acl + "\n" + String.valueOf(project) + "\n" + session;
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(scope.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public boolean securityEnabled() {
        return Boolean.TRUE.equals(properties.getSecurity().getEnabled());
    }

    public boolean canAccessProject(String project) {
        AuthenticatedUser user = currentUser();
        if (user.canAccessAllProjects()) {
            return true;
        }
        if (!StringUtils.hasText(project)) {
            return false;
        }
        String normalizedProject = normalize(project);
        return user.projects().stream().map(this::normalize).anyMatch(normalizedProject::equals);
    }

    /** 读取与写入分权；只有管理员和编辑者能发布知识文档。 */
    public void requireDocumentWrite() {
        if (!securityEnabled()) return;
        String role=currentUser().role();
        if (!"ADMIN".equals(role) && !"EDITOR".equals(role)) throw new AccessDeniedException("当前角色没有文档写入权限");
    }

    public void requireProjectAccess(String project) {
        if (!canAccessProject(project)) {
            throw new AccessDeniedException("无权访问项目：" + (StringUtils.hasText(project) ? project : "未指定项目"));
        }
    }

    public List<String> restrictedProjects() {
        AuthenticatedUser user = currentUser();
        return user.canAccessAllProjects() ? List.of() : user.projects();
    }

    private String normalize(String value) {
        return String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    }
}
