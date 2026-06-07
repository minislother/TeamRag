package com.team.rag.bean;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CurrentUserResponse {

    private Boolean securityEnabled;
    private Boolean authenticated;
    private String username;
    private String role;
    private List<String> projects;
}
