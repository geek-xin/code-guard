package com.geek.codeguard.sca.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.geek.codeguard.common.enums.ErrorCodeEnum;
import com.geek.codeguard.common.exception.BusinessException;
import com.geek.codeguard.config.CodeGuardProperties;
import com.geek.codeguard.config.JsonStore;
import com.geek.codeguard.sca.model.Vulnerability;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * OSV.dev 在线漏洞查询客户端（带本地缓存）。
 */
@Service
@Slf4j
public class OsvClient {

    private static final String OSV_QUERY_URL = "https://api.osv.dev/v1/query";
    private static final long CACHE_TTL_MS = 7L * 24 * 3600 * 1000;

    private final CodeGuardProperties props;
    private final JsonStore jsonStore;
    private final HttpClient http;
    private final Map<String, List<Vulnerability>> memoryCache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<List<Vulnerability>>> inflight = new ConcurrentHashMap<>();
    private final Semaphore limiter;
    private final ExecutorService pool;

    public OsvClient(CodeGuardProperties props, JsonStore jsonStore) {
        this.props = props;
        this.jsonStore = jsonStore;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        int concurrency = Math.max(1, props.getScaOsvConcurrency());
        this.limiter = new Semaphore(concurrency);
        this.pool = Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r, "osv-query-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
    }

    /** 异步查询：相同包+版本只发一次网络请求（in-flight 合并），限流并发数 */
    public CompletableFuture<List<Vulnerability>> queryAsync(String ecosystem, String name, String version) {
        if (!props.getSca().isOsvEnabled()) {
            return CompletableFuture.completedFuture(List.of());
        }
        String cacheKey = key(ecosystem, name, version);
        if (memoryCache.containsKey(cacheKey)) {
            return CompletableFuture.completedFuture(memoryCache.get(cacheKey));
        }
        CompletableFuture<List<Vulnerability>> existing = inflight.putIfAbsent(cacheKey,
                CompletableFuture.supplyAsync(() -> doQuery(cacheKey, ecosystem, name, version), pool));
        if (existing != null) {
            return existing;
        }
        return inflight.get(cacheKey).whenComplete((r, e) -> inflight.remove(cacheKey));
    }

    /** 查询某个包的全部已知漏洞（用于漏洞库更新），无缓存 */
    public List<Vulnerability> queryPackage(String ecosystem, String name) {
        if (!props.getSca().isOsvEnabled()) {
            return List.of();
        }
        try {
            String payload = jsonStore.mapper().writeValueAsString(Map.of(
                    "package", Map.of("name", name, "ecosystem", ecosystem)));
            HttpRequest req = HttpRequest.newBuilder(URI.create(OSV_QUERY_URL))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(props.getSca().getOsvTimeoutMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("OSV 包查询失败 {}: HTTP {}", name, resp.statusCode());
                return List.of();
            }
            return parse(resp.body());
        } catch (Exception e) {
            log.debug("OSV 包查询异常 {}: {}", name, e.getMessage());
            return List.of();
        }
    }

    public List<Vulnerability> query(String ecosystem, String name, String version) {
        try {
            return queryAsync(ecosystem, name, version).get(props.getSca().getOsvTimeoutMs() + 3000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("OSV 查询失败 {}: {}", name, e.getMessage());
            return List.of();
        }
    }

    private List<Vulnerability> doQuery(String cacheKey, String ecosystem, String name, String version) {
        if (memoryCache.containsKey(cacheKey)) {
            return memoryCache.get(cacheKey);
        }
        Path cacheFile = jsonStore.paths().osvCache.resolve(cacheKey + ".json");
        List<Vulnerability> fromCache = readCache(cacheFile);
        if (fromCache != null) {
            memoryCache.put(cacheKey, fromCache);
            return fromCache;
        }
        try {
            if (!limiter.tryAcquire(props.getSca().getOsvTimeoutMs(), TimeUnit.MILLISECONDS)) {
                log.debug("OSV 并发限流超时: {}", name);
                return List.of();
            }
            try {
                String payload = jsonStore.mapper().writeValueAsString(Map.of(
                        "package", Map.of("name", name, "ecosystem", ecosystem),
                        "version", version));
                HttpRequest req = HttpRequest.newBuilder(URI.create(OSV_QUERY_URL))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofMillis(props.getSca().getOsvTimeoutMs()))
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    log.warn("OSV 查询失败 {}: HTTP {}", name, resp.statusCode());
                    memoryCache.put(cacheKey, List.of());
                    return List.of();
                }
                List<Vulnerability> vulns = parse(resp.body());
                writeCache(cacheFile, vulns);
                memoryCache.put(cacheKey, vulns);
                return vulns;
            } finally {
                limiter.release();
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.debug("OSV 查询异常 {}: {}", name, e.getMessage());
            return List.of();
        }
    }

    public List<Vulnerability> parse(String body) throws IOException {
        List<Vulnerability> result = new ArrayList<>();
        JsonNode root = jsonStore.mapper().readTree(body);
        JsonNode items = root.path("vulns");
        if (!items.isArray()) {
            return result;
        }
        for (JsonNode item : items) {
            Vulnerability.VulnerabilityBuilder builder = Vulnerability.builder()
                    .id(item.path("id").asText())
                    .summary(item.path("summary").asText(null))
                    .details(item.path("details").asText(null))
                    .published(item.path("published").asText(null))
                    .modified(item.path("modified").asText(null))
                    .severity(extractSeverity(item));
            List<String> aliases = new ArrayList<>();
            item.path("aliases").forEach(a -> aliases.add(a.asText()));
            builder.aliases(aliases);
            List<String> refs = new ArrayList<>();
            item.path("references").forEach(r -> refs.add(r.path("url").asText()));
            builder.references(refs);
            List<Vulnerability.Affected> affected = new ArrayList<>();
            for (JsonNode a : item.path("affected")) {
                String pkg = a.path("package").path("name").asText();
                String eco = a.path("package").path("ecosystem").asText();
                List<String> ranges = new ArrayList<>();
                for (JsonNode r : a.path("ranges")) {
                    StringBuilder range = new StringBuilder();
                    boolean first = true;
                    for (JsonNode ev : r.path("events")) {
                        String introduced = ev.path("introduced").asText(null);
                        String fixed = ev.path("fixed").asText(null);
                        if (introduced != null && !introduced.equals("0")) {
                            if (!first) range.append(", ");
                            range.append(">=").append(introduced);
                            first = false;
                        } else if (introduced != null) {
                            first = true;
                        }
                        if (fixed != null) {
                            if (!first) range.append(", ");
                            range.append("< ").append(fixed);
                            first = false;
                        }
                    }
                    if (!range.isEmpty()) {
                        ranges.add(range.toString());
                    }
                }
                List<String> versions = new ArrayList<>();
                a.path("versions").forEach(v -> versions.add(v.asText()));
                affected.add(Vulnerability.Affected.builder()
                        .ecosystem(eco)
                        .packageName(pkg)
                        .ranges(ranges)
                        .versions(versions)
                        .build());
            }
            builder.affected(affected);
            result.add(builder.build());
        }
        return result;
    }

    private String extractSeverity(JsonNode item) {
        JsonNode dbSpec = item.path("database_specific");
        String sev = dbSpec.path("severity").asText(null);
        if (sev != null) {
            return normalizeSeverity(sev);
        }
        for (JsonNode s : item.path("severity")) {
            if ("CVSS_V3".equals(s.path("type").asText()) || "CVSS_V4".equals(s.path("type").asText())) {
                String score = s.path("score").asText("");
                return scoreToSeverity(score);
            }
        }
        return "UNKNOWN";
    }

    private String normalizeSeverity(String s) {
        return switch (s.toUpperCase()) {
            case "CRITICAL" -> "CRITICAL";
            case "HIGH" -> "HIGH";
            case "MODERATE", "MEDIUM" -> "MEDIUM";
            case "LOW" -> "LOW";
            default -> "UNKNOWN";
        };
    }

    private String scoreToSeverity(String score) {
        try {
            double v = Double.parseDouble(score);
            if (v >= 9.0) return "CRITICAL";
            if (v >= 7.0) return "HIGH";
            if (v >= 4.0) return "MEDIUM";
            return "LOW";
        } catch (NumberFormatException e) {
            return "UNKNOWN";
        }
    }

    private String key(String ecosystem, String name, String version) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((ecosystem + "|" + name + "|" + version).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 24);
        } catch (Exception e) {
            return Integer.toHexString((ecosystem + name + version).hashCode());
        }
    }

    private List<Vulnerability> readCache(Path file) {
        try {
            if (!Files.exists(file)) {
                return null;
            }
            long age = System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis();
            if (age > CACHE_TTL_MS) {
                return null;
            }
            return jsonStore.mapper().readValue(Files.readString(file),
                    new com.fasterxml.jackson.core.type.TypeReference<List<Vulnerability>>() {
                    });
        } catch (Exception e) {
            return null;
        }
    }

    private void writeCache(Path file, List<Vulnerability> vulns) {
        try {
            jsonStore.write(file, vulns);
        } catch (Exception e) {
            // 缓存失败不影响扫描
        }
    }
}
