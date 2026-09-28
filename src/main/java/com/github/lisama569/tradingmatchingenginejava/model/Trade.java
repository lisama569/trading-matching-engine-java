package com.github.lisama569.tradingmatchingenginejava.model;

/**
 * 一笔成交流水（taker 与 resting maker 撮合产生），不可变。
 * 成交价取 maker 的挂单价（价格优先的边界保持确定）。
 */
public record Trade(
        long tradeId,
        long takerOrderId,
        long makerOrderId,
        long takerAccountId,
        long makerAccountId,
        String symbol,
        Side takerSide,
        long price,
        long qty,
        long timestampMillis
) {
    /** 该笔成交中买方账户 id，便于账务侧归类。 */
    public long buyerAccountId() {
        return takerSide == Side.BUY ? takerAccountId : makerAccountId;
    }

    /** 该笔成交中卖方账户 id。 */
    public long sellerAccountId() {
        return takerSide == Side.SELL ? takerAccountId : makerAccountId;
    }

    /** 成交金额（分）。 */
    public long notional() {
        return price * qty;
    }
}
