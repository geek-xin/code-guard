package com.geek.codeguard.project.controller;

import com.geek.codeguard.common.enums.ErrorCodeEnum;
import com.geek.codeguard.common.exception.BusinessException;
import com.geek.codeguard.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务器文件系统目录浏览（用于本地目录选择，兼容 Windows / Linux / macOS）。
 */
@RestController
@RequestMapping("/api/projects/browse")
public class DirectoryController {

    @GetMapping
    public Mono<Result<Map<String, Object>>> browse(@RequestParam(defaultValue = "") String path) {
        return Mono.fromCallable(() -> {
            Path current;
            try {
                if (path == null || path.isBlank()) {
                    current = Path.of("").toAbsolutePath().normalize();
                } else {
                    current = Path.of(path).toAbsolutePath().normalize();
                }
            } catch (Exception e) {
                throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "路径格式不正确: " + path);
            }
            if (!Files.isDirectory(current)) {
                throw new BusinessException(ErrorCodeEnum.SOURCE_NOT_FOUND, "目录不存在: " + current);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("current", current.toString());
            Path parent = current.getParent();
            result.put("parent", parent == null ? null : parent.toString());
            result.put("name", current.getFileName() == null ? current.toString() : current.getFileName().toString());

            List<Map<String, String>> dirs = new ArrayList<>();
            try (var stream = Files.list(current)) {
                stream.filter(Files::isDirectory)
                        .sorted()
                        .forEach(d -> {
                            Map<String, String> item = new LinkedHashMap<>();
                            item.put("name", d.getFileName() == null ? d.toString() : d.getFileName().toString());
                            item.put("path", d.toString());
                            dirs.add(item);
                        });
            } catch (IOException e) {
                throw new BusinessException(ErrorCodeEnum.INTERNAL_ERROR, "读取目录失败: " + e.getMessage());
            }
            result.put("dirs", dirs);
            return result;
        }).map(Result::success);
    }
}
