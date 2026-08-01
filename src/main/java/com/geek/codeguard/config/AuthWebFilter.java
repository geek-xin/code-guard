package com.geek.codeguard.config;

import com.geek.codeguard.auth.model.User;
import com.geek.codeguard.auth.service.TokenService;
import com.geek.codeguard.common.constants.CommonConstants;
import com.geek.codeguard.common.enums.ErrorCodeEnum;
import com.geek.codeguard.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Set;

@Component
@Order(-100)
@Slf4j
public class AuthWebFilter implements WebFilter {

    private final TokenService tokenService;

    /** 无需登录即可访问的路径 */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/auth/login", "/api/auth/register", "/api/auth/github/authorize",
            "/api/auth/github/callback", "/api/auth/gitlab/authorize", "/api/auth/gitlab/callback",
            "/api/health", "/health"
    );

    public AuthWebFilter(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (HttpMethod.OPTIONS.equals(exchange.getRequest().getMethod())
                || path.startsWith("/api/auth/") && !path.startsWith("/api/auth/me")
                || PUBLIC_PATHS.contains(path)
                || path.startsWith("/assets/")
                || path.equals("/") || path.equals("/index.html")
                || path.startsWith("/api/auth/callback-redirect")
                || !path.startsWith("/api/")) {
            return chain.filter(exchange);
        }
        String header = exchange.getRequest().getHeaders().getFirst(CommonConstants.HEADER_AUTH);
        String token = header != null && header.startsWith(CommonConstants.TOKEN_PREFIX)
                ? header.substring(CommonConstants.TOKEN_PREFIX.length()).trim()
                : (header != null ? header.trim() : null);
        // 兜底：允许 ?token= 查询参数（用于浏览器直链下载报告等场景）
        if (token == null || token.isBlank()) {
            String q = exchange.getRequest().getQueryParams().getFirst("token");
            if (q != null && !q.isBlank()) {
                token = q.trim();
            }
        }
        try {
            User user = tokenService.verify(token);
            exchange.getAttributes().put(CommonConstants.CURRENT_USER_ATTR, user);
            return chain.filter(exchange);
        } catch (BusinessException e) {
            exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
            exchange.getResponse().getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            byte[] body = ("{\"success\":false,\"code\":\"" + e.getCode() + "\",\"message\":\"" + e.getMessage() + "\"}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return exchange.getResponse().writeWith(reactor.core.publisher.Mono.just(
                    exchange.getResponse().bufferFactory().wrap(body)));
        }
    }
}
