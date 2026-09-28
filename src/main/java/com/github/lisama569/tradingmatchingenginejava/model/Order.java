package com.github.lisama569.tradingmatchingenginejava.model;

/**
 * 内存中的委托单。所有可变状态（filledQty、status）只会在对应标的的撮合锁内被修改，
 * 因而读侧看到的字段始终是自洽的。
 */
public final class Order {

    private final long id;
    /** 全局递增序列号，同价位时严格按该序列号排序，保证时间优先是确定的。 */
    private final long seq;
    private final long accountId;
    private final String symbol;
    private final Side side;
    private final OrderType type;
    /** 限价单价格（单位：最小货币单位/分）；市价单为 null。 */
    private final Long limitPrice;
    private final long origQty;
    private final long createTimeMillis;

    private long filledQty;
    /** 累计成交金额（price*qty，单位：分），用于计算 VWAP。 */
    private long filledNotional;
    /**
     * 买单冻结的资金中尚未交割的部分（分）；卖单该值始终为 0。
     * 下单时设置，成交时按成交额消耗，撤单 / IOC 终结时把剩余值一次性释放。
     */
    private long frozenCash;
    /**
     * 卖单冻结的持仓中尚未交割的部分（股）；买单该值始终为 0。
     */
    private long frozenQty;
    private OrderStatus status;
    private RejectCode rejectReason;

    public Order(long id, long seq, long accountId, String symbol, Side side, OrderType type,
                 Long limitPrice, long origQty, long createTimeMillis) {
        this.id = id;
        this.seq = seq;
        this.accountId = accountId;
        this.symbol = symbol;
        this.side = side;
        this.type = type;
        this.limitPrice = limitPrice;
        this.origQty = origQty;
        this.createTimeMillis = createTimeMillis;
        this.filledQty = 0;
        this.filledNotional = 0;
        this.status = OrderStatus.NEW;
    }

    public long remainingQty() {
        return origQty - filledQty;
    }

    public boolean isActive() {
        return status == OrderStatus.NEW || status == OrderStatus.PARTIALLY_FILLED;
    }

    /** 撮合引擎专用：记录一笔成交并推进状态。 */
    public void recordFill(long qty, long price) {
        if (qty <= 0 || qty > remainingQty()) {
            throw new IllegalStateException("illegal fill qty=" + qty + " for order " + id);
        }
        filledQty += qty;
        filledNotional += qty * price;
        status = (filledQty == origQty) ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    }

    /** 撤单 / 市价单 IOC 剩余终结。终结后不能再成交。 */
    public void markCanceled(RejectCode reason) {
        if (status == OrderStatus.FILLED || status == OrderStatus.CANCELED || status == OrderStatus.REJECTED) {
            throw new IllegalStateException("order " + id + " already terminal: " + status);
        }
        this.status = OrderStatus.CANCELED;
        this.rejectReason = reason;
    }

    public void markRejected(RejectCode reason) {
        this.status = OrderStatus.REJECTED;
        this.rejectReason = reason;
    }

    /** 下单风控通过后记录冻结额。买单记录资金冻结，卖单记录持仓冻结。 */
    public void initFreeze(long cashForBuy, long qtyForSell) {
        this.frozenCash = cashForBuy;
        this.frozenQty = qtyForSell;
    }

    /** 成交交割时消耗已冻结资金（买方）。 */
    public void consumeFrozenCash(long notional) {
        if (notional < 0 || notional > frozenCash) {
            throw new IllegalStateException("consumeFrozenCash " + notional + " > " + frozenCash
                    + " for order " + id);
        }
        frozenCash -= notional;
    }

    /** 成交交割时消耗已冻结持仓（卖方）。 */
    public void consumeFrozenQty(long qty) {
        if (qty < 0 || qty > frozenQty) {
            throw new IllegalStateException("consumeFrozenQty " + qty + " > " + frozenQty
                    + " for order " + id);
        }
        frozenQty -= qty;
    }

    public long getId() { return id; }
    public long getSeq() { return seq; }
    public long getAccountId() { return accountId; }
    public String getSymbol() { return symbol; }
    public Side getSide() { return side; }
    public OrderType getType() { return type; }
    public Long getLimitPrice() { return limitPrice; }
    public long getOrigQty() { return origQty; }
    public long getFilledQty() { return filledQty; }
    public long getFilledNotional() { return filledNotional; }
    public OrderStatus getStatus() { return status; }
    public RejectCode getRejectReason() { return rejectReason; }
    public long getCreateTimeMillis() { return createTimeMillis; }
    public long getFrozenCash() { return frozenCash; }
    public long getFrozenQty() { return frozenQty; }

    /** 成交均价（单位：分），无成交时返回 0。 */
    public long getAvgFillPrice() {
        return filledQty == 0 ? 0 : filledNotional / filledQty;
    }

    @Override
    public String toString() {
        return "Order{" + "id=" + id + ", account=" + accountId + ", symbol='" + symbol + '\''
                + ", side=" + side + ", type=" + type
                + ", price=" + (limitPrice == null ? "MKT" : limitPrice)
                + ", origQty=" + origQty + ", filledQty=" + filledQty
                + ", status=" + status + (rejectReason == null ? "" : ", reason=" + rejectReason) + '}';
    }
}
