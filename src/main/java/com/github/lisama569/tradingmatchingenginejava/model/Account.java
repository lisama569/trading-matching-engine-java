package com.github.lisama569.tradingmatchingenginejava.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 资金账户：现金、冻结现金以及各标的持仓的聚合根。
 * <p>每个账户内置一把 {@link #lock()}。同一账户的下单冻结、成交交割、撤单释放
 * 全部串行执行，从而保证：
 * <ul>
 *     <li>availableCash = cash - frozenCash 永不为负；</li>
 *     <li>持仓 available = qty - frozenQty 永不为负；</li>
 *     <li>一笔委托的冻结只释放一次、成交只交割一次。</li>
 * </ul>
 */
public final class Account {

    private final long id;
    private final String name;
    private long cash;
    private long frozenCash;
    private final Map<String, Position> positions = new LinkedHashMap<>();

    public Account(long id, String name, long initialCash) {
        if (initialCash < 0) {
            throw new IllegalArgumentException("initial cash must be non-negative");
        }
        this.id = id;
        this.name = name;
        this.cash = initialCash;
        this.frozenCash = 0;
    }

    /** 账户级互斥锁。调用方必须 try/finally unlock。 */
    public Object lock() {
        return this;
    }

    // ---------------- 现金 ----------------

    public long availableCash() {
        return cash - frozenCash;
    }

    /** 买入下单冻结资金；不足由调用方转为 INSUFFICIENT_CASH 拒绝。 */
    public boolean freezeCash(long amount) {
        if (amount <= 0 || amount > availableCash()) {
            return false;
        }
        frozenCash += amount;
        return true;
    }

    public void releaseFrozenCash(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("release amount must be positive");
        }
        if (amount > frozenCash) {
            throw new IllegalStateException("release " + amount + " > frozenCash " + frozenCash
                    + " for account " + id);
        }
        frozenCash -= amount;
    }

    /** 买入成交交割：把冻结资金转为支付，买方现金净额减少。 */
    public void settleBuyCash(long notional) {
        if (notional <= 0 || notional > frozenCash) {
            throw new IllegalStateException("illegal buy settle " + notional + " for account " + id
                    + ", frozenCash=" + frozenCash);
        }
        frozenCash -= notional;
        cash -= notional;
    }

    /** 卖出成交交割：卖方现金增加。 */
    public void settleSellCash(long notional) {
        if (notional <= 0) {
            throw new IllegalArgumentException("settle amount must be positive");
        }
        cash += notional;
    }

    // ---------------- 持仓 ----------------

    /** 获取持仓，不存在时惰性创建一个零持仓。 */
    public Position positionOf(String symbol) {
        return positions.computeIfAbsent(symbol, s -> new Position(s, 0));
    }

    /** 初始化（bootstrap）使用，直接给一批持仓。 */
    public void depositPosition(String symbol, long qty) {
        Position p = positions.get(symbol);
        if (p == null) {
            positions.put(symbol, new Position(symbol, qty));
        } else {
            p.settleBuy(qty);
        }
    }

    public Map<String, Position> snapshotPositions() {
        return Map.copyOf(positions);
    }

    public long getId() { return id; }
    public String getName() { return name; }
    public long getCash() { return cash; }
    public long getFrozenCash() { return frozenCash; }

    @Override
    public String toString() {
        return "Account{id=" + id + ", name='" + name + "', cash=" + cash
                + ", frozenCash=" + frozenCash + ", available=" + availableCash() + '}';
    }
}
