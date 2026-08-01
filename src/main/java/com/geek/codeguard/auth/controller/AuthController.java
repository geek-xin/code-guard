package com.geek.codeguard.auth.controller;

import com.geek.codeguard.auth.model.SessionUser;
import com.geek.codeguard.auth.model.User;
import com.geek.codeguard.auth.service.OAuthService;
import com.geek.codeguard.auth.service.TokenService;
import com.geek.codeguard.auth.service.UserStore;
import com.geek.codeguard.common.constants.CommonConstants;
import com.geek.codeguard.common.enums.ErrorCodeEnum;
import com.geek.codeguard.common.exception.BusinessException;
import com.geek.codeguard.common.result.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@Slf4j
public class AuthController {

    private final UserStore userStore;
    private final TokenService tokenService;
    private final OAuthService oauthService;

    public AuthController(UserStore userStore, TokenService tokenService, OAuthService oauthService) {
        this.userStore = userStore;
        this.tokenService = tokenService;
        this.oauthService = oauthService;
    }

    @Data
    public static class LoginRequest {
        @NotBlank(message = "用户名不能为空")
        private String username;
        @NotBlank(message = "密码不能为空")
        private String password;
        /** 记住登录（30 天） */
        private boolean remember;
    }

    @Data
    public static class RegisterRequest {
        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 32, message = "用户名长度需在 3-32 之间")
        private String username;
        @NotBlank(message = "密码不能为空")
        @Size(min = 6, max = 64, message = "密码长度需在 6-64 之间")
        private String password;
        private String displayName;
        private boolean remember;
    }

    @PostMapping("/register")
    public Mono<Result<Map<String, Object>>> register(@Valid @RequestBody RegisterRequest req) {
        return Mono.fromCallable(() -> {
            User user = userStore.createLocal(req.getUsername().trim(), req.getPassword(), req.getDisplayName());
            String token = tokenService.issue(user, req.isRemember());
            return Result.success(Map.of("token", token, "user", SessionUser.from(user)));
        });
    }

    @PostMapping("/login")
    public Mono<Result<Map<String, Object>>> login(@Valid @RequestBody LoginRequest req) {
        return Mono.fromCallable(() -> {
            User user = userStore.verifyLocal(req.getUsername().trim(), req.getPassword());
            String token = tokenService.issue(user, req.isRemember());
            return Result.success(Map.of("token", token, "user", SessionUser.from(user)));
        });
    }

    @GetMapping("/me")
    public Mono<Result<SessionUser>> me(ServerWebExchange exchange) {
        User user = (User) exchange.getAttribute(CommonConstants.CURRENT_USER_ATTR);
        return Mono.just(Result.success(SessionUser.from(user)));
    }

    @PostMapping("/logout")
    public Mono<Result<Void>> logout() {
        // 无状态 token，前端清除即可
        return Mono.just(Result.success());
    }

    @GetMapping("/github/authorize")
    public Mono<Result<Map<String, String>>> githubAuthorize(@RequestParam(defaultValue = "false") boolean remember) {
        String url = oauthService.githubAuthorizeUrl(remember);
        return Mono.just(Result.success(Map.of("url", url)));
    }

    @GetMapping("/gitlab/authorize")
    public Mono<Result<Map<String, String>>> gitlabAuthorize(@RequestParam(defaultValue = "false") boolean remember) {
        String url = oauthService.gitlabAuthorizeUrl(remember);
        return Mono.just(Result.success(Map.of("url", url)));
    }

    @GetMapping("/github/callback")
    public Mono<ResponseEntity<Void>> githubCallback(@RequestParam String code, @RequestParam(required = false) String state) {
        return Mono.fromCallable(() -> {
            User user = oauthService.exchangeGithub(code);
            User saved = userStore.upsertOAuth(user);
            boolean remember = oauthService.parseRemember(state);
            String token = tokenService.issue(saved, remember);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create("/#/auth/callback?token=" + token + "&remember=" + remember))
                    .build();
        });
    }

    @GetMapping("/gitlab/callback")
    public Mono<ResponseEntity<Void>> gitlabCallback(@RequestParam String code, @RequestParam(required = false) String state) {
        return Mono.fromCallable(() -> {
            User user = oauthService.exchangeGitlab(code);
            User saved = userStore.upsertOAuth(user);
            boolean remember = oauthService.parseRemember(state);
            String token = tokenService.issue(saved, remember);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create("/#/auth/callback?token=" + token + "&remember=" + remember))
                    .build();
        });
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleAuthError(BusinessException e) {
        if (ErrorCodeEnum.OAUTH_FAILED.getCode().equals(e.getCode())) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create("/#/auth/callback?error=" + e.getMessage()))
                    .build();
        }
        return ResponseEntity.badRequest().body(Result.failure(e.getCode(), e.getMessage()));
    }
}
