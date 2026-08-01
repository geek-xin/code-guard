package com.geek.codeguard.auth.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {
    private String id;
    /** 登录名（本地注册 / OAuth 用户名） */
    private String username;
    private String passwordHash;
    /** LOCAL / GITHUB / GITLAB */
    private String provider;
    private String providerUserId;
    private String displayName;
    private String avatarUrl;
    private String email;
    private String accessToken;
    private List<String> roles = new ArrayList<>();
    private boolean enabled = true;
    private Instant createdAt;
    private Instant lastLoginAt;
}
