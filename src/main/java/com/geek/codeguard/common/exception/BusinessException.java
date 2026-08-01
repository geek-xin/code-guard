package com.geek.codeguard.common.exception;

import com.geek.codeguard.common.enums.ErrorCodeEnum;
import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final String code;

    public BusinessException(ErrorCodeEnum error) {
        super(error.getMessage());
        this.code = error.getCode();
    }

    public BusinessException(ErrorCodeEnum error, String message) {
        super(message);
        this.code = error.getCode();
    }

    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }
}
