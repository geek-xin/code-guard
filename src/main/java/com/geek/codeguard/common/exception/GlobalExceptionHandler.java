package com.geek.codeguard.common.exception;

import com.geek.codeguard.common.enums.ErrorCodeEnum;
import com.geek.codeguard.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException e) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        if (ErrorCodeEnum.UNAUTHORIZED.getCode().equals(e.getCode())
                || ErrorCodeEnum.TOKEN_INVALID.getCode().equals(e.getCode())) {
            status = HttpStatus.UNAUTHORIZED;
        } else if (ErrorCodeEnum.NOT_FOUND.getCode().equals(e.getCode())
                || ErrorCodeEnum.PROJECT_NOT_FOUND.getCode().equals(e.getCode())
                || ErrorCodeEnum.SCAN_NOT_FOUND.getCode().equals(e.getCode())) {
            status = HttpStatus.NOT_FOUND;
        }
        return ResponseEntity.status(status).body(Result.failure(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<Result<Void>> handleBind(WebExchangeBindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(Result.failure(ErrorCodeEnum.BAD_REQUEST.getCode(), msg));
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<Result<Void>> handleInput(ServerWebInputException e) {
        return ResponseEntity.badRequest().body(Result.failure(ErrorCodeEnum.BAD_REQUEST.getCode(), e.getReason() == null ? "请求体解析失败" : e.getReason()));
    }

    @ExceptionHandler(org.springframework.web.reactive.resource.NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResource(org.springframework.web.reactive.resource.NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.failure(ErrorCodeEnum.NOT_FOUND.getCode(), "资源不存在: " + e.getMessage()));
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<Result<Void>> handleOther(Throwable e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.failure(ErrorCodeEnum.INTERNAL_ERROR.getCode(), e.getMessage() == null ? "系统内部错误" : e.getMessage()));
    }
}
