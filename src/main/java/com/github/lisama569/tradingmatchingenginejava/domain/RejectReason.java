package com.github.lisama569.tradingmatchingenginejava.domain;

/** 风控/撮合拒绝原因，对外可区分。 */
public enum RejectReason {
    INVALID_SYMBOL,
    INVALID_ACCOUNT,
    INVALID_SIDE,
    INVALID_ORDER_TYPE,
    INVALID_PRICE,
    INVALID_QUANTITY,
    INSUFFICIENT_CASH,
    INSUFFICIENT_POSITION,
    EXCEEDS_LIMIT,
    ORDER_NOT_FOUND,
    ORDER_ALREADY_FINISHED,
    MARKET_NO_LIQUIDITY,
    SYSTEM_ERROR
}
