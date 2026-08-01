package com.geek.codeguard.auth.service;

import com.geek.codeguard.auth.model.User;
import com.geek.codeguard.common.enums.ErrorCodeEnum;
import com.geek.codeguard.common.exception.BusinessException;
import com.geek.codeguard.config.JsonStore;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class UserStore {

    private final JsonStore jsonStore;
    private final PasswordHasher passwordHasher;
    private final Map<String, User> byId = new ConcurrentHashMap<>();
    private final Map<String, User> byName = new ConcurrentHashMap<>();

    public UserStore(JsonStore jsonStore, PasswordHasher passwordHasher) {
        this.jsonStore = jsonStore;
        this.passwordHasher = passwordHasher;
    }

    @PostConstruct
    public synchronized void init() {
        Path file = jsonStore.paths().users.resolve("users.json");
        List<User> users = jsonStore.read(file, new com.fasterxml.jackson.core.type.TypeReference<List<User>>() {
        });
        byId.clear();
        byName.clear();
        if (users != null) {
            for (User u : users) {
                byId.put(u.getId(), u);
                byName.put(u.getUsername().toLowerCase(), u);
            }
        }
        if (byId.isEmpty()) {
            User admin = User.builder()
                    .id(UUID.randomUUID().toString())
                    .username("admin")
                    .passwordHash(passwordHasher.hash("admin123"))
                    .provider("LOCAL")
                    .displayName("管理员")
                    .roles(List.of("ADMIN"))
                    .enabled(true)
                    .createdAt(Instant.now())
                    .build();
            save(admin);
            log.warn("已创建默认管理员账号 admin / admin123，请尽快修改密码");
        }
    }

    public List<User> list() {
        return new ArrayList<>(byId.values());
    }

    public Optional<User> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<User> findByUsername(String username) {
        return Optional.ofNullable(byName.get(username == null ? "" : username.toLowerCase()));
    }

    public Optional<User> findByProvider(String provider, String providerUserId) {
        return byId.values().stream()
                .filter(u -> provider.equals(u.getProvider()) && providerUserId.equals(u.getProviderUserId()))
                .findFirst();
    }

    public User createLocal(String username, String rawPassword, String displayName) {
        if (findByUsername(username).isPresent()) {
            throw new BusinessException(ErrorCodeEnum.PROJECT_NAME_EXISTS, "用户名已存在");
        }
        User user = User.builder()
                .id(UUID.randomUUID().toString())
                .username(username)
                .passwordHash(passwordHasher.hash(rawPassword))
                .provider("LOCAL")
                .displayName(displayName == null || displayName.isBlank() ? username : displayName)
                .roles(List.of("USER"))
                .enabled(true)
                .createdAt(Instant.now())
                .lastLoginAt(Instant.now())
                .build();
        save(user);
        return user;
    }

    public User verifyLocal(String username, String rawPassword) {
        User user = findByUsername(username)
                .orElseThrow(() -> new BusinessException(ErrorCodeEnum.AUTH_FAILED));
        if (!user.isEnabled()) {
            throw new BusinessException(ErrorCodeEnum.AUTH_DISABLED);
        }
        if (user.getPasswordHash() == null || !passwordHasher.verify(rawPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCodeEnum.AUTH_FAILED);
        }
        user.setLastLoginAt(Instant.now());
        save(user);
        return user;
    }

    public User upsertOAuth(User oauthUser) {
        User existing = findByProvider(oauthUser.getProvider(), oauthUser.getProviderUserId()).orElse(null);
        if (existing == null) {
            String base = oauthUser.getUsername();
            String username = base;
            int i = 1;
            while (findByUsername(username).isPresent()) {
                username = base + "_" + (i++);
            }
            oauthUser.setId(UUID.randomUUID().toString());
            oauthUser.setUsername(username);
            oauthUser.setRoles(List.of("USER"));
            oauthUser.setEnabled(true);
            oauthUser.setCreatedAt(Instant.now());
            save(oauthUser);
            return oauthUser;
        }
        existing.setDisplayName(oauthUser.getDisplayName());
        existing.setAvatarUrl(oauthUser.getAvatarUrl());
        existing.setEmail(oauthUser.getEmail());
        if (oauthUser.getAccessToken() != null) {
            existing.setAccessToken(oauthUser.getAccessToken());
        }
        existing.setLastLoginAt(Instant.now());
        save(existing);
        return existing;
    }

    public void save(User user) {
        Path file = jsonStore.paths().users.resolve("users.json");
        List<User> all = new ArrayList<>(byId.values());
        all.removeIf(u -> u.getId().equals(user.getId()));
        all.add(user);
        byId.put(user.getId(), user);
        byName.put(user.getUsername().toLowerCase(), user);
        jsonStore.write(file, all);
    }
}
