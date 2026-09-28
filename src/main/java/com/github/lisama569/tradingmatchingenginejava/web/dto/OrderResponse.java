package com.github.lisama569.tradingmatchingenginejava.web.dto;

import com.github.lisama569.tradingmatchingenginejava.model.Order;

import java.util.List;

/**
 * 下单 / 撤单的结构化结果。
 * <p>注意：市价单无对手盘时 HTTP 仍为 200、{@code accepted=true}，
 * 订单按 IOC 规则终结为 CANCELED 且 {@code rejectReason=NO_LIQUIDITY}，
 * 这与风控拒绝（HTTP 4xx、订单不存在）明确区分。
 */
public class OrderResponse {

    private boolean accepted;
    private long orderId;
    private String symbol;
    private String side;
    private String type;
    private Long price;
    private long origQty;
    private long filledQty;
    private long remainingQty;
    private long avgFillPrice;
    private String status;
    private String rejectReason;
    private List<TradeView> trades;

    public static OrderResponse of(Order order, List<TradeView> trades) {
        OrderResponse r = new OrderResponse();
        r.accepted = true;
        r.orderId = order.getId();
        r.symbol = order.getSymbol();
        r.side = order.getSide().name();
        r.type = order.getType().name();
        r.price = order.getLimitPrice();
        r.origQty = order.getOrigQty();
        r.filledQty = order.getFilledQty();
        r.remainingQty = order.remainingQty();
        r.avgFillPrice = order.getAvgFillPrice();
        r.status = order.getStatus().name();
        r.rejectReason = order.getRejectReason() == null ? null : order.getRejectReason().name();
        r.trades = trades;
        return r;
    }

    /** 单笔成交视图。 */
    public static class TradeView {
        public long tradeId;
        public long price;
        public long qty;
        public long counterpartyOrderId;
        public long timestampMillis;

        public TradeView(long tradeId, long price, long qty, long counterpartyOrderId, long timestampMillis) {
            this.tradeId = tradeId;
            this.price = price;
            this.qty = qty;
            this.counterpartyOrderId = counterpartyOrderId;
            this.timestampMillis = timestampMillis;
        }
    }

    public boolean isAccepted() { return accepted; }
    public long getOrderId() { return orderId; }
    public String getSymbol() { return symbol; }
    public String getSide() { return side; }
    public String getType() { return type; }
    public Long getPrice() { return price; }
    public long getOrigQty() { return origQty; }
    public long getFilledQty() { return filledQty; }
    public long getRemainingQty() { return remainingQty; }
    public long getAvgFillPrice() { return avgFillPrice; }
    public String getStatus() { return status; }
    public String getRejectReason() { return rejectReason; }
    public List<TradeView> getTrades() { return trades; }
}
