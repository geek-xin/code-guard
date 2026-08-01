package com.geek.codeguard.web.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 前端入口：/ 与 /admin 返回构建后的 React 管理台。
 */
@RestController
public class HomeController {

    @GetMapping({"/", "/admin", "/admin/**"})
    public Mono<ResponseEntity<Resource>> index() {
        return Mono.just(ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(new ClassPathResource("static/admin/index.html")));
    }
}
