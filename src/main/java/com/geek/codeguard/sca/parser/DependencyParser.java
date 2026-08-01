package com.geek.codeguard.sca.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.geek.codeguard.config.JsonStore;
import com.geek.codeguard.sca.model.Dependency;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 依赖清单解析：package.json / pom.xml / requirements.txt / go.mod / Gemfile / composer.json。
 */
@Component
public class DependencyParser {

    private final JsonStore jsonStore;

    public DependencyParser(JsonStore jsonStore) {
        this.jsonStore = jsonStore;
    }

    public boolean isManifest(Path file) {
        String name = file.getFileName().toString();
        return name.equals("package.json") || name.equals("pom.xml") || name.equals("requirements.txt")
                || name.equals("go.mod") || name.equals("Gemfile") || name.equals("composer.json")
                || name.equals("build.gradle") || name.equals("build.gradle.kts");
    }

    public List<Dependency> parse(Path file) {
        String name = file.getFileName().toString();
        try {
            return switch (name) {
                case "package.json" -> parsePackageJson(file);
                case "pom.xml" -> parsePom(file);
                case "requirements.txt" -> parseRequirements(file);
                case "go.mod" -> parseGoMod(file);
                case "Gemfile" -> parseGemfile(file);
                case "composer.json" -> parseComposer(file);
                default -> List.of();
            };
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Dependency> parsePackageJson(Path file) throws IOException {
        List<Dependency> deps = new ArrayList<>();
        JsonNode root = jsonStore.mapper().readTree(Files.readString(file));
        collectDeps(root, "dependencies", "npm", file, deps, false);
        collectDeps(root, "devDependencies", "npm", file, deps, false);
        return deps;
    }

    private void collectDeps(JsonNode root, String field, String ecosystem, Path file, List<Dependency> out, boolean transitive) {
        JsonNode node = root.path(field);
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                String version = e.getValue().isTextual() ? e.getValue().asText() : "*";
                out.add(Dependency.builder()
                        .ecosystem(ecosystem)
                        .name(e.getKey())
                        .version(version)
                        .manifest(rel(file))
                        .transitive(transitive)
                        .build());
            });
        }
    }

    private List<Dependency> parsePom(Path file) throws IOException {
        List<Dependency> deps = new ArrayList<>();
        JsonNode root = jsonStore.xmlMapper().readTree(Files.readString(file));
        JsonNode depsNode = root.path("dependencies").path("dependency");
        if (depsNode.isArray()) {
            for (JsonNode d : depsNode) {
                deps.add(Dependency.builder()
                        .ecosystem("Maven")
                        .name(d.path("groupId").asText() + ":" + d.path("artifactId").asText())
                        .version(d.path("version").asText("*"))
                        .manifest(rel(file))
                        .build());
            }
        } else if (depsNode.isObject()) {
            deps.add(Dependency.builder()
                    .ecosystem("Maven")
                    .name(depsNode.path("groupId").asText() + ":" + depsNode.path("artifactId").asText())
                    .version(depsNode.path("version").asText("*"))
                    .manifest(rel(file))
                    .build());
        }
        return deps;
    }

    private List<Dependency> parseRequirements(Path file) throws IOException {
        List<Dependency> deps = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#") || s.startsWith("-") || s.startsWith("--")) {
                continue;
            }
            // name==1.2.3 | name>=1.0 | name[extra]==1.2.3
            String[] ops = {"==", ">=", "<=", ">", "<", "=", "~=", "!="};
            String name = s;
            String version = "*";
            int idx = -1;
            for (String op : ops) {
                idx = s.indexOf(op);
                if (idx > 0) {
                    name = s.substring(0, idx);
                    version = s.substring(idx + op.length()).trim().split("[;, ]")[0];
                    break;
                }
            }
            if (name.contains("[")) {
                name = name.substring(0, name.indexOf('['));
            }
            deps.add(Dependency.builder()
                    .ecosystem("PyPI")
                    .name(name.toLowerCase())
                    .version(version)
                    .manifest(rel(file))
                    .build());
        }
        return deps;
    }

    private List<Dependency> parseGoMod(Path file) throws IOException {
        List<Dependency> deps = new ArrayList<>();
        boolean inRequire = false;
        boolean inBlock = false;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.startsWith("require (")) {
                inBlock = true;
                continue;
            }
            if (inBlock && line.equals(")")) {
                inBlock = false;
                continue;
            }
            if (line.startsWith("require ")) {
                String[] parts = line.substring("require ".length()).trim().split("\\s+");
                if (parts.length >= 2) {
                    deps.add(Dependency.builder()
                            .ecosystem("Go")
                            .name(parts[0])
                            .version(parts[1])
                            .manifest(rel(file))
                            .build());
                }
                continue;
            }
            if (inBlock) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 2 && !parts[0].startsWith("//")) {
                    deps.add(Dependency.builder()
                            .ecosystem("Go")
                            .name(parts[0])
                            .version(parts[1])
                            .manifest(rel(file))
                            .build());
                }
            }
        }
        return deps;
    }

    private List<Dependency> parseGemfile(Path file) throws IOException {
        List<Dependency> deps = new ArrayList<>();
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.startsWith("gem ")) {
                String content = line.substring(4).trim();
                String[] parts = content.split(",");
                String name = parts[0].replaceAll("^['\"]|['\"]$", "").trim();
                String version = "*";
                if (parts.length > 1) {
                    String v = parts[1].trim();
                    // 解析 '~> 1.2' / '>= 1.0' 等
                    String[] vp = v.replaceAll("^['\"]|['\"]$", "").split("\\s+");
                    if (vp.length >= 2 && vp[0].matches("[~>=<\\^]+")) {
                        version = vp[0] + " " + vp[1];
                    } else {
                        version = v;
                    }
                }
                deps.add(Dependency.builder()
                        .ecosystem("RubyGems")
                        .name(name)
                        .version(version)
                        .manifest(rel(file))
                        .build());
            }
        }
        return deps;
    }

    private List<Dependency> parseComposer(Path file) throws IOException {
        List<Dependency> deps = new ArrayList<>();
        JsonNode root = jsonStore.mapper().readTree(Files.readString(file));
        collectDeps(root, "require", "Packagist", file, deps, false);
        collectDeps(root, "require-dev", "Packagist", file, deps, false);
        return deps;
    }

    private String rel(Path file) {
        return file.toString();
    }
}
