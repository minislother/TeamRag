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
