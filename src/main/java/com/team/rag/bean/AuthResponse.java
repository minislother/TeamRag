package com.team.rag.bean;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AuthResponse {

    private Boolean securityEnabled;
    private String token;
    private String username;
    private String role;
    private List<String> projects;
}
