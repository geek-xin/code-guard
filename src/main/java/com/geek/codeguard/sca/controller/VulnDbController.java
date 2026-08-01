package com.geek.codeguard.sca.controller;

import com.geek.codeguard.common.result.Result;
import com.geek.codeguard.sca.service.VulnDbUpdateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/vulndb")
public class VulnDbController {

    private final VulnDbUpdateService updateService;

    public VulnDbUpdateService getUpdateService() {
        return updateService;
    }

    public VulnDbController(VulnDbUpdateService updateService) {
        this.updateService = updateService;
    }

    @GetMapping("/status")
    public Mono<Result<Map<String, Object>>> status() {
        return Mono.just(Result.success(updateService.status()));
    }

    /** 手动触发漏洞库更新（后台异步执行） */
    @PostMapping("/update")
    public Mono<Result<Map<String, Object>>> update() {
        boolean started = updateService.update();
        return Mono.just(Result.success(Map.of(
                "started", started,
                "message", started ? "漏洞库更新已启动，完成后自动生效" : "漏洞库正在更新中，请稍后再试"
        )));
    }
}
