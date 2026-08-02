package com.geek.codeguard.sca.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Dependency {
    private String ecosystem;
    private String name;
    private String version;
    /** 所在清单文件（相对路径） */
    private String manifest;
}
