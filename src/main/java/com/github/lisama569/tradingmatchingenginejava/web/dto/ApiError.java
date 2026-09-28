package com.github.lisama569.tradingmatchingenginejava.web.dto;

/**
 * 统一错误响应。只暴露稳定的错误码与可读原因，绝不把内部异常堆栈透传给调用方。
 */
public class ApiError {

    private final String code;
    private final String message;

    public ApiError(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String getCode() { return code; }
    public String getMessage() { return message; }
}
