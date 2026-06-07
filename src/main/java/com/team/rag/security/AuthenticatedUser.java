package com.team.rag.security;

import java.util.List;

public record AuthenticatedUser(String username, String role, List<String> projects) {

    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(role);
    }

    public boolean canAccessAllProjects() {
        return isAdmin() || projects.stream().anyMatch("*"::equals);
    }
}
