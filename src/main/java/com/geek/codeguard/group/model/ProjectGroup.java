package com.geek.codeguard.group.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 项目分组（独立管理，项目通过下拉选择分组）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectGroup {
    private String id;
    private String name;
    private String color;
    private Instant createdAt;
}
