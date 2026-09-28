package com.github.lisama569.tradingmatchingenginejava.domain;

/**
 * 订单。金额单位：分（price），数量单位：股（quantity），全部为整数，避免浮点误差。
 *
 * 线程模型：订单进入撮合后，其可变字段（filledQty / avgPrice / status）只在
 * 所属 symbol 的单线程执行器中修改；下单阶段的冻结在入队前完成，撤单的解冻
 * 在同一执行器内随状态变更一起完成，因此不需要额外同步。
 */
public final class Order {

    private final long id;
    private final String accountId;
    private final String symbol;
    private final Side side;
    private final OrderType type;
    /** 限价单价（分）；市价单为 0，撮合时取对手盘挂单价。 */
    private final long price;
    private final long quantity;
    /** 同 symbol 内单调递增的入队序号，决定同价时间优先。 */
    private final long sequence;
    /** 入簿时间戳（毫秒），仅用于可观测展示，不参与排序。 */
    private final long createdAt;

    private long filledQty;
    /** 累计成交金额（分），平均价 = filledNotional / filledQty。 */
    private long filledNotional;
    private OrderStatus status;

    public Order(long id, String accountId, String symbol, Side side, OrderType type,
                 long price, long quantity, long sequence, long createdAt) {
        this.id = id;
        this.accountId = accountId;
        this.symbol = symbol;
        this.side = side;
        this.type = type;
        this.price = price;
        this.quantity = quantity;
        this.sequence = sequence;
        this.createdAt = createdAt;
        this.filledQty = 0L;
        this.filledNotional = 0L;
        this.status = OrderStatus.NEW;
    }

    public long getId() { return id; }
    public String getAccountId() { return accountId; }
    public String getSymbol() { return symbol; }
    public Side getSide() { return side; }
    public OrderType getType() { return type; }
    public long getPrice() { return price; }
    public long getQuantity() { return quantity; }
    public long getSequence() { return sequence; }
    public long getCreatedAt() { return createdAt; }
    public long getFilledQty() { return filledQty; }
    public long getFilledNotional() { return filledNotional; }

    public long getAvgFillPrice() {
        return filledQty == 0 ? 0L : filledNotional / filledQty;
    }

    public long getRemainingQty() {
        return quantity - filledQty;
    }

    public OrderStatus getStatus() { return status; }

    /** 是否处于已结束状态（不再可能成交或撤销）。 */
    public boolean isFinished() {
        return status == OrderStatus.FILLED
                || status == OrderStatus.CANCELED
                || status == OrderStatus.REJECTED;
    }

    /** 记录一次成交，仅在 symbol 执行线程内调用。 */
    public void applyFill(long matchPrice, long matchQty) {
        this.filledQty += matchQty;
        this.filledNotional += matchPrice * matchQty;
        this.status = (filledQty >= quantity) ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    }

    /** 标记撤销（市价单余量作废、限价单主动撤单），仅在 symbol 执行线程内调用。 */
    public void markCanceled() {
        this.status = OrderStatus.CANCELED;
    }
}
