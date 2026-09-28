package com.github.lisama569.tradingmatchingenginejava.model;

/**
 * 单标的持仓。{@code qty} 为净持仓，{@code frozenQty} 为卖出委托冻结的部分。
 * 恒等式：0 <= frozenQty <= qty，即可用持仓 {@code available = qty - frozenQty} 永不为负。
 * 所有方法必须在账户锁内调用（见 AccountService）。
 */
public final class Position {

    private final String symbol;
    private long qty;
    private long frozenQty;

    public Position(String symbol, long initialQty) {
        if (initialQty < 0) {
            throw new IllegalArgumentException("initial qty must be non-negative");
        }
        this.symbol = symbol;
        this.qty = initialQty;
        this.frozenQty = 0;
    }

    /** 卖出下单时冻结持仓；不足由调用方转为 INSUFFICIENT_POSITION 拒绝。 */
    public boolean freeze(long amount) {
        if (amount <= 0 || amount > available()) {
            return false;
        }
        frozenQty += amount;
        return true;
    }

    /** 撤单 / IOC 剩余：释放冻结。 */
    public void releaseFrozen(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("release amount must be positive");
        }
        if (amount > frozenQty) {
            throw new IllegalStateException("release " + amount + " > frozen " + frozenQty + " for " + symbol);
        }
        frozenQty -= amount;
    }

    /** 买入成交交割：持仓增加。 */
    public void settleBuy(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("settle amount must be positive");
        }
        qty += amount;
    }

    /** 卖出成交交割：冻结与持仓同时扣减，保证不会把持仓扣成负数。 */
    public void settleSell(long amount) {
        if (amount <= 0 || amount > frozenQty) {
            throw new IllegalStateException("illegal sell settle " + amount + " for " + symbol
                    + ", frozen=" + frozenQty);
        }
        qty -= amount;
        frozenQty -= amount;
    }

    public long available() {
        return qty - frozenQty;
    }

    public String getSymbol() { return symbol; }
    public long getQty() { return qty; }
    public long getFrozenQty() { return frozenQty; }

    @Override
    public String toString() {
        return "Position{" + symbol + ", qty=" + qty + ", frozen=" + frozenQty
                + ", available=" + available() + '}';
    }
}
