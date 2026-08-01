package com.geek.codeguard.common.enums;

import lombok.Getter;

@Getter
public enum ErrorCodeEnum {
    SUCCESS("0", "success"),
    BAD_REQUEST("400", "请求参数错误"),
    UNAUTHORIZED("401", "未登录或登录已过期"),
    FORBIDDEN("403", "无权访问"),
    NOT_FOUND("404", "资源不存在"),
    CONFLICT("409", "资源冲突"),
    INTERNAL_ERROR("500", "系统内部错误"),

    // auth
    AUTH_FAILED("1001", "用户名或密码错误"),
    AUTH_DISABLED("1002", "账号已禁用"),
    OAUTH_FAILED("1003", "OAuth 登录失败"),
    TOKEN_INVALID("1004", "登录凭证无效或已过期"),

    // project
    PROJECT_NOT_FOUND("2001", "项目不存在"),
    PROJECT_NAME_EXISTS("2002", "项目名称已存在"),
    CLONE_FAILED("2003", "代码拉取失败"),
    SOURCE_NOT_FOUND("2004", "本地源码目录不存在"),

    // scan
    SCAN_NOT_FOUND("3001", "扫描记录不存在"),
    SCAN_RUNNING("3002", "该项目正在扫描中"),
    SCAN_FAILED("3003", "扫描执行失败"),
    FILE_TOO_LARGE("3004", "文件超过分析大小上限"),

    // sca
    VULNDB_LOAD_FAILED("4001", "漏洞库加载失败"),

    // agent
    AGENT_FAILED("5001", "代码审查 Agent 调用失败");

    private final String code;
    private final String message;

    ErrorCodeEnum(String code, String message) {
        this.code = code;
        this.message = message;
    }
}
