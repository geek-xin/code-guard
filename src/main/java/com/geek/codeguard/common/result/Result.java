package com.geek.codeguard.common.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {
    private boolean success;
    private String code;
    private String message;
    private T data;

    public static <T> Result<T> success(T data) {
        return new Result<>(true, "0", "success", data);
    }

    public static Result<Void> success() {
        return new Result<>(true, "0", "success", null);
    }

    public static <T> Result<T> failure(String code, String message) {
        return new Result<>(false, code, message, null);
    }
}
