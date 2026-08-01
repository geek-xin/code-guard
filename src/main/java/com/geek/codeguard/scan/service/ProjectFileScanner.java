package com.geek.codeguard.scan.service;

import com.geek.codeguard.config.CodeGuardProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/**
 * 项目文件遍历：应用 ignore 模式（glob），返回相对路径列表。
 */
@Component
public class ProjectFileScanner {

    private final List<PathMatcher> ignoreMatchers;

    public ProjectFileScanner(CodeGuardProperties props) {
        this.ignoreMatchers = new ArrayList<>();
        for (String pattern : props.getIgnorePatterns()) {
            try {
                ignoreMatchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
            } catch (Exception ignored) {
            }
        }
        // 内置：总是忽略 .git
        ignoreMatchers.add(FileSystems.getDefault().getPathMatcher("glob:**/.git/**"));
    }

    public List<Path> listFiles(Path root) {
        List<Path> result = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !isIgnored(root, p))
                    .sorted()
                    .forEach(result::add);
        } catch (IOException e) {
            throw new IllegalStateException("遍历项目文件失败: " + root, e);
        }
        return result;
    }

    public boolean isIgnored(Path root, Path file) {
        Path rel = root.relativize(file);
        for (PathMatcher m : ignoreMatchers) {
            if (m.matches(rel) || m.matches(file)) {
                return true;
            }
        }
        return false;
    }

    public String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
