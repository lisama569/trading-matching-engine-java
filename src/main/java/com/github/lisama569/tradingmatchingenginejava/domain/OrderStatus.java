package com.github.lisama569.tradingmatchingenginejava.domain;

/**
 * 订单状态。
 * NEW 未成交挂单中；PARTIALLY_FILLED 部分成交仍在簿；FILLED 全部成交；
 * CANCELED 已撤销（含市价单未成交余量作废）；REJECTED 下单即被风控拒绝。
 */
public enum OrderStatus {
    NEW,
    PARTIALLY_FILLED,
    FILLED,
    CANCELED,
    REJECTED
}
