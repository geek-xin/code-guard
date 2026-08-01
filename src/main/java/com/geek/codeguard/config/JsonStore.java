package com.geek.codeguard.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 与 web-sim 一致的本地 JSON 文件存储：每个实体一个 JSON 文件，原子写入。
 */
@Component
@Slf4j
public class JsonStore {

    private final CodeGuardProperties props;
    private final ObjectMapper mapper;
    private final XmlMapper xmlMapper;

    public JsonStore(CodeGuardProperties props) {
        this.props = props;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.mapper.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        this.xmlMapper = new XmlMapper();
    }

    @PostConstruct
    public void init() {
        CodeGuardProperties.Paths paths = props.resolvePaths();
        for (Path p : List.of(paths.data, paths.workspace, paths.repositories,
                paths.scans, paths.vulndb, paths.rules, paths.users, paths.osvCache)) {
            try {
                Files.createDirectories(p);
            } catch (IOException e) {
                throw new IllegalStateException("无法创建数据目录: " + p, e);
            }
        }
        log.info("CodeGuard 数据目录: {} , 工作区: {}", paths.data, paths.workspace);
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public XmlMapper xmlMapper() {
        return xmlMapper;
    }

    public CodeGuardProperties props() {
        return props;
    }

    public CodeGuardProperties.Paths paths() {
        return props.resolvePaths();
    }

    public <T> T read(Path file, Class<T> type) {
        try {
            if (!Files.exists(file)) {
                return null;
            }
            return mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), type);
        } catch (IOException e) {
            throw new IllegalStateException("读取 JSON 失败: " + file, e);
        }
    }

    public <T> T read(Path file, com.fasterxml.jackson.core.type.TypeReference<T> typeRef) {
        try {
            if (!Files.exists(file)) {
                return null;
            }
            return mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), typeRef);
        } catch (IOException e) {
            throw new IllegalStateException("读取 JSON 失败: " + file, e);
        }
    }

    public <T> List<T> readList(Path dir, Class<T> type) {
        List<T> result = new ArrayList<>();
        try {
            if (!Files.isDirectory(dir)) {
                return result;
            }
            try (var stream = Files.list(dir)) {
                List<Path> files = stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .sorted().toList();
                for (Path f : files) {
                    T t = read(f, type);
                    if (t != null) {
                        result.add(t);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取目录失败: " + dir, e);
        }
        return result;
    }

    public void write(Path file, Object value) {
        try {
            Files.createDirectories(file.getParent());
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("写入 JSON 失败: " + file, e);
        }
    }

    public void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new IllegalStateException("删除文件失败: " + file, e);
        }
    }

    public List<Path> listJsonFiles(Path dir) {
        List<Path> result = new ArrayList<>();
        try {
            if (!Files.isDirectory(dir)) {
                return result;
            }
            try (var stream = Files.list(dir)) {
                result = stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .sorted().collect(java.util.stream.Collectors.toList());
            }
        } catch (IOException e) {
            throw new IllegalStateException("列出目录失败: " + dir, e);
        }
        return result;
    }

    /** 扫描目录下所有文件（跳过忽略模式） */
    public List<Path> walkFiles(Path root, Predicate<Path> ignore) {
        List<Path> files = new ArrayList<>();
        try {
            try (var stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> !ignore.test(p))
                        .sorted()
                        .forEach(files::add);
            }
        } catch (IOException e) {
            throw new IllegalStateException("遍历目录失败: " + root, e);
        }
        return files;
    }
}
