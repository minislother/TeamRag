package com.team.rag.security;

import com.team.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class UserAccountService {

    private final RagProperties properties;
    private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private volatile String loadedUsers;
    private volatile Map<String, UserAccount> accounts = Map.of();

    public AuthenticatedUser authenticate(String username, String password) {
        UserAccount account = accounts().get(normalize(username));
        if (account == null || !matches(password, account.password())) {
            throw new BadCredentialsException("用户名或密码错误");
        }
        return account.toAuthenticatedUser();
    }

    public UserAccount requireUser(String username) {
        UserAccount account = accounts().get(normalize(username));
        if (account == null) {
            throw new BadCredentialsException("登录用户不存在");
        }
        return account;
    }

    private Map<String, UserAccount> accounts() {
        String configuredUsers = properties.getSecurity().getUsers();
        if (configuredUsers != null && configuredUsers.equals(loadedUsers)) {
            return accounts;
        }
        synchronized (this) {
            if (configuredUsers != null && configuredUsers.equals(loadedUsers)) {
                return accounts;
            }
            accounts = parseAccounts(configuredUsers);
            loadedUsers = configuredUsers;
            return accounts;
        }
    }

    private Map<String, UserAccount> parseAccounts(String configuredUsers) {
        Map<String, UserAccount> parsed = new ConcurrentHashMap<>();
        if (!StringUtils.hasText(configuredUsers)) {
            return parsed;
        }
        for (String item : configuredUsers.split(";")) {
            if (!StringUtils.hasText(item)) {
                continue;
            }
            String[] parts = item.split(":", 4);
            if (parts.length < 3) {
                throw new IllegalArgumentException("Invalid rag.security.users item: " + item);
            }
            String username = parts[0].trim();
            String password = parts[1].trim();
            String role = parts[2].trim().toUpperCase(Locale.ROOT);
            List<String> projects = parts.length >= 4 && StringUtils.hasText(parts[3])
                    ? Arrays.stream(parts[3].split("\\|")).map(String::trim).filter(StringUtils::hasText).toList()
                    : List.of();
            parsed.put(normalize(username), new UserAccount(username, password, role, projects));
        }
        return parsed;
    }

    private boolean matches(String rawPassword, String storedPassword) {
        if (storedPassword.startsWith("{")) {
            return passwordEncoder.matches(rawPassword, storedPassword);
        }
        return storedPassword.equals(rawPassword);
    }

    private String normalize(String username) {
        return String.valueOf(username).trim().toLowerCase(Locale.ROOT);
    }

    public record UserAccount(String username, String password, String role, List<String> projects) {

        public AuthenticatedUser toAuthenticatedUser() {
            return new AuthenticatedUser(username, role, projects == null ? List.of() : projects);
        }
    }
}
