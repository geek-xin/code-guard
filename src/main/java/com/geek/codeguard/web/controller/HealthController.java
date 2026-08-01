package com.geek.codeguard.web.controller;

import com.geek.codeguard.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/api/health")
    public Mono<Result<Map<String, String>>> health() {
        return Mono.just(Result.success(Map.of("status", "UP", "service", "codeguard")));
    }
}
