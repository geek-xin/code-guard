package com.geek.codeguard.auth.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SessionUser {
    private String id;
    private String username;
    private String displayName;
    private String avatarUrl;
    private String email;
    private String provider;
    private boolean providerTokenConfigured;
    private List<String> roles;

    public static SessionUser from(User u) {
        return SessionUser.builder()
                .id(u.getId())
                .username(u.getUsername())
                .displayName(u.getDisplayName())
                .avatarUrl(u.getAvatarUrl())
                .email(u.getEmail())
                .provider(u.getProvider())
                .providerTokenConfigured(u.getAccessToken() != null && !u.getAccessToken().isBlank())
                .roles(u.getRoles())
                .build();
    }
}
