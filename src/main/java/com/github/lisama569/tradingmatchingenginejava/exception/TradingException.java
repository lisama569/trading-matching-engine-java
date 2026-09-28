package com.github.lisama569.tradingmatchingenginejava.exception;

import com.github.lisama569.tradingmatchingenginejava.domain.RejectReason;

/** 业务异常：携带可区分的拒绝原因，不向外暴露内部堆栈。 */
public class TradingException extends RuntimeException {
    private final RejectReason reason;

    public TradingException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason getReason() { return reason; }
}
