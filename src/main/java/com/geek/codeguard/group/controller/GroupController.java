package com.geek.codeguard.group.controller;

import com.geek.codeguard.common.result.Result;
import com.geek.codeguard.group.model.ProjectGroup;
import com.geek.codeguard.group.service.GroupService;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/groups")
public class GroupController {

    private final GroupService groupService;

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    @Data
    public static class GroupRequest {
        @NotBlank(message = "分组名称不能为空")
        private String name;
    }

    @GetMapping
    public Mono<Result<List<ProjectGroup>>> list() {
        return Mono.just(Result.success(groupService.list()));
    }

    @PostMapping
    public Mono<Result<ProjectGroup>> create(@RequestBody GroupRequest req) {
        return Mono.fromCallable(() -> groupService.create(req.getName())).map(Result::success);
    }

    @PutMapping("/{id}")
    public Mono<Result<ProjectGroup>> rename(@PathVariable String id, @RequestBody GroupRequest req) {
        return Mono.fromCallable(() -> groupService.rename(id, req.getName())).map(Result::success);
    }

    @DeleteMapping("/{id}")
    public Mono<Result<Void>> delete(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            groupService.delete(id);
            return Result.<Void>success();
        });
    }
}
