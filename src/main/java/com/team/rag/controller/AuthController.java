package com.team.rag.controller;

import com.team.rag.bean.AuthResponse;
import com.team.rag.bean.CurrentUserResponse;
import com.team.rag.bean.LoginRequest;
import com.team.rag.security.AccessControlService;
import com.team.rag.security.AuthenticatedUser;
import com.team.rag.security.SecurityTokenService;
import com.team.rag.security.UserAccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AccessControlService accessControlService;
    private final UserAccountService userAccountService;
    private final SecurityTokenService tokenService;

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        AuthenticatedUser user = userAccountService.authenticate(request.getUsername(), request.getPassword());
        return AuthResponse.builder()
                .securityEnabled(accessControlService.securityEnabled())
                .token(tokenService.issue(user))
                .username(user.username())
                .role(user.role())
                .projects(user.projects())
                .build();
    }

    @GetMapping("/me")
    public CurrentUserResponse me() {
        AuthenticatedUser user = accessControlService.currentUser();
        return CurrentUserResponse.builder()
                .securityEnabled(accessControlService.securityEnabled())
                .authenticated(accessControlService.securityEnabled())
                .username(user.username())
                .role(user.role())
                .projects(user.projects())
                .build();
    }
}
