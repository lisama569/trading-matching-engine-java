package com.github.lisama569.tradingmatchingenginejava.domain;

/**
 * 一笔成交（taker 订单与某个 maker 挂单撮合产生）。
 * 成交价取 maker 挂单价；taker 为 BUY 时对手是 SELL，反之亦然。
 */
public final class Trade {

    private final long tradeId;
    private final String symbol;
    private final long price;
    private final long quantity;
    private final long takerOrderId;
    private final long makerOrderId;
    private final String takerAccount;
    private final String makerAccount;
    private final Side takerSide;
    private final long createdAt;

    public Trade(long tradeId, String symbol, long price, long quantity,
                 long takerOrderId, long makerOrderId,
                 String takerAccount, String makerAccount,
                 Side takerSide, long createdAt) {
        this.tradeId = tradeId;
        this.symbol = symbol;
        this.price = price;
        this.quantity = quantity;
        this.takerOrderId = takerOrderId;
        this.makerOrderId = makerOrderId;
        this.takerAccount = takerAccount;
        this.makerAccount = makerAccount;
        this.takerSide = takerSide;
        this.createdAt = createdAt;
    }

    public long getTradeId() { return tradeId; }
    public String getSymbol() { return symbol; }
    public long getPrice() { return price; }
    public long getQuantity() { return quantity; }
    public long getTakerOrderId() { return takerOrderId; }
    public long getMakerOrderId() { return makerOrderId; }
    public String getTakerAccount() { return takerAccount; }
    public String getMakerAccount() { return makerAccount; }
    public Side getTakerSide() { return takerSide; }
    public long getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return "Trade{id=" + tradeId + ", symbol=" + symbol
                + ", price=" + price + ", qty=" + quantity
                + ", taker=" + takerOrderId + "(" + takerAccount + ")"
                + ", maker=" + makerOrderId + "(" + makerAccount + ")"
                + ", takerSide=" + takerSide + "}";
    }
}
