package com.github.lisama569.tradingmatchingenginejava.common;

import com.github.lisama569.tradingmatchingenginejava.model.RejectCode;

/**
 * 对外可解释的业务异常。message 只包含可读的判定依据，不携带内部堆栈信息；
 * 由 GlobalExceptionHandler 统一转成结构化错误响应。
 */
public class TradingException extends RuntimeException {

    private final RejectCode code;

    public TradingException(RejectCode code, String message) {
        super(message);
        this.code = code;
    }

    public TradingException(RejectCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public RejectCode getCode() {
        return code;
    }
}
