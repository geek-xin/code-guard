package com.geek.codeguard.sca.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.geek.codeguard.config.JsonStore;
import com.geek.codeguard.sca.model.Vulnerability;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 漏洞库更新：从 OSV.dev 拉取重点包的最新漏洞数据，生成/覆盖本地离线库。
 * 支持手动触发（API）与定时更新（每天 03:30）。
 */
@Service
@Slf4j
public class VulnDbUpdateService {

    private static final Map<String, List<String>> FOCUS_PACKAGES = Map.ofEntries(
            Map.entry("npm", List.of("lodash", "minimist", "axios", "jsonwebtoken", "tar", "express",
                    "ejs", "next", "ansi-regex", "glob-parent", "node-fetch", "qs", "path-parse",
                    "shelljs", "vm2", "handlebars", "moment", "pug", "serialize-javascript", "semver")),
            Map.entry("Maven", List.of("org.apache.logging.log4j:log4j-core",
                    "org.springframework:spring-core", "org.springframework:spring-webmvc",
                    "org.yaml:snakeyaml", "org.apache.commons:commons-text", "com.alibaba:fastjson",
                    "com.fasterxml.jackson.core:jackson-databind",
                    "org.apache.tomcat.embed:tomcat-embed-core",
                    "commons-fileupload:commons-fileupload", "org.apache.shiro:shiro-core")),
            Map.entry("PyPI", List.of("urllib3", "django", "requests", "jinja2", "pillow", "flask",
                    "werkzeug", "sqlalchemy", "cryptography", "starlette", "fastapi", "pydantic")),
            Map.entry("Go", List.of("golang.org/x/net", "golang.org/x/crypto", "golang.org/x/text",
                    "github.com/gin-gonic/gin", "github.com/docker/docker")),
            Map.entry("RubyGems", List.of("rack", "rails", "nokogiri", "actionpack", "devise")),
            Map.entry("Packagist", List.of("symfony/http-foundation", "laravel/framework",
                    "guzzlehttp/guzzle", "phpunit/phpunit"))
    );

    private final JsonStore jsonStore;
    private final OsvClient osvClient;
    private final VulnerabilityDbService dbService;
    private final AtomicBoolean updating = new AtomicBoolean(false);

    public VulnDbUpdateService(JsonStore jsonStore, OsvClient osvClient, VulnerabilityDbService dbService) {
        this.jsonStore = jsonStore;
        this.osvClient = osvClient;
        this.dbService = dbService;
    }

    /** 每天 03:30 定时更新漏洞库 */
    @Scheduled(cron = "0 30 3 * * ?")
    public void scheduledUpdate() {
        log.info("定时漏洞库更新开始");
        update();
    }

    /** 从 OSV 拉取并更新本地漏洞库；返回是否成功启动 */
    public boolean update() {
        if (!updating.compareAndSet(false, true)) {
            return false;
        }
        Thread updater = new Thread(this::doUpdate, "vulndb-updater");
        updater.setDaemon(true);
        updater.start();
        return true;
    }

    private void doUpdate() {
        try {
            List<Vulnerability> all = new ArrayList<>();
            for (Map.Entry<String, List<String>> eco : FOCUS_PACKAGES.entrySet()) {
                for (String pkg : eco.getValue()) {
                    try {
                        List<Vulnerability> vulns = osvClient.queryPackage(eco.getKey(), pkg);
                        List<Vulnerability> keep = vulns.stream()
                                .filter(v -> severityRank(v.getSeverity()) >= 2)
                                .sorted(Comparator.comparingInt((Vulnerability v) -> -severityRank(v.getSeverity())))
                                .limit(6)
                                .toList();
                        all.addAll(keep);
                    } catch (Exception e) {
                        log.debug("更新漏洞库 {}:{} 失败: {}", eco.getKey(), pkg, e.getMessage());
                    }
                }
            }
            all.sort(Comparator.comparingInt((Vulnerability v) -> -severityRank(v.getSeverity())));
            Path dbFile = jsonStore.paths().vulndb.resolve("codeguard-vulndb.json");
            Path tmp = dbFile.resolveSibling("codeguard-vulndb.json.tmp");
            jsonStore.write(tmp, all);
            Files.move(tmp, dbFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            writeMeta(all.size());
            dbService.reload();
            log.info("漏洞库更新完成：{} 条，写入 {}", all.size(), dbFile);
        } catch (Exception e) {
            log.error("漏洞库更新失败", e);
        } finally {
            updating.set(false);
        }
    }

    private void writeMeta(int count) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("version", DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneId.systemDefault()).format(Instant.now()));
        meta.put("lastUpdatedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        meta.put("count", count);
        meta.put("nextScheduledUpdate", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
                .format(java.time.LocalDate.now().atTime(3, 30).plusDays(1).atZone(ZoneId.systemDefault()).toInstant()));
        jsonStore.write(jsonStore.paths().vulndb.resolve("meta.json"), meta);
    }

    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> meta = jsonStore.read(jsonStore.paths().vulndb.resolve("meta.json"),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
        if (meta == null) {
            meta = new LinkedHashMap<>();
        }
        result.put("count", dbService.totalLoaded());
        result.put("lastUpdatedAt", meta.get("lastUpdatedAt"));
        result.put("version", meta.get("version"));
        result.put("nextScheduledUpdate", meta.get("nextScheduledUpdate"));
        result.put("osvEnabled", jsonStore.props().getSca().isOsvEnabled());
        result.put("updating", updating.get());
        return result;
    }

    private int severityRank(String sev) {
        return switch (sev == null ? "" : sev.toUpperCase()) {
            case "CRITICAL" -> 4;
            case "HIGH" -> 3;
            case "MEDIUM", "MODERATE" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }
}
