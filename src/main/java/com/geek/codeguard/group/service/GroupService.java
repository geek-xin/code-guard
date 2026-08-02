package com.geek.codeguard.group.service;

import com.geek.codeguard.common.enums.ErrorCodeEnum;
import com.geek.codeguard.common.exception.BusinessException;
import com.geek.codeguard.config.JsonStore;
import com.geek.codeguard.group.model.ProjectGroup;
import com.geek.codeguard.project.model.Project;
import com.geek.codeguard.project.service.ProjectService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 分组管理：config/groups.json 持久化；删除分组时清理引用它的项目。
 */
@Service
@Slf4j
public class GroupService {

    private static final String[] COLORS = {"#CB3837", "#FF8A00", "#3775A9", "#00ADD8", "#D63384", "#7C3AED", "#18A96B", "#E9573F", "#F6C445"};

    private final JsonStore jsonStore;
    private final ProjectService projectService;

    public GroupService(JsonStore jsonStore, ProjectService projectService) {
        this.jsonStore = jsonStore;
        this.projectService = projectService;
    }

    private Path file() {
        return jsonStore.paths().data.resolve("groups.json");
    }

    public List<ProjectGroup> list() {
        List<ProjectGroup> groups = jsonStore.read(file(), new com.fasterxml.jackson.core.type.TypeReference<List<ProjectGroup>>() {
        });
        return groups == null ? new ArrayList<>() : groups;
    }

    public ProjectGroup create(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "分组名称不能为空");
        }
        if (list().stream().anyMatch(g -> g.getName().equalsIgnoreCase(n))) {
            throw new BusinessException(ErrorCodeEnum.ALIAS_EXISTS, "分组「" + n + "」已存在");
        }
        ProjectGroup group = ProjectGroup.builder()
                .id(UUID.randomUUID().toString())
                .name(n)
                .color(COLORS[list().size() % COLORS.length])
                .createdAt(Instant.now())
                .build();
        List<ProjectGroup> all = list();
        all.add(group);
        save(all);
        return group;
    }

    public ProjectGroup rename(String id, String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "分组名称不能为空");
        }
        List<ProjectGroup> all = list();
        ProjectGroup target = all.stream().filter(g -> g.getId().equals(id))
                .findFirst().orElseThrow(() -> new BusinessException(ErrorCodeEnum.NOT_FOUND, "分组不存在"));
        if (all.stream().anyMatch(g -> !g.getId().equals(id) && g.getName().equalsIgnoreCase(n))) {
            throw new BusinessException(ErrorCodeEnum.ALIAS_EXISTS, "分组「" + n + "」已存在");
        }
        String oldName = target.getName();
        target.setName(n);
        save(all);
        // 同步更新引用该分组的项目
        for (Project p : projectService.list()) {
            if (oldName.equals(p.getGroup())) {
                p.setGroup(n);
                projectService.save(p);
            }
        }
        return target;
    }

    public void delete(String id) {
        List<ProjectGroup> all = list();
        ProjectGroup target = all.stream().filter(g -> g.getId().equals(id))
                .findFirst().orElseThrow(() -> new BusinessException(ErrorCodeEnum.NOT_FOUND, "分组不存在"));
        all.remove(target);
        save(all);
        // 清理项目中对该分组的引用
        for (Project p : projectService.list()) {
            if (target.getName().equals(p.getGroup())) {
                p.setGroup(null);
                projectService.save(p);
            }
        }
    }

    private void save(List<ProjectGroup> groups) {
        jsonStore.write(file(), groups);
    }
}
