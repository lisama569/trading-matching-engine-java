package com.github.lisama569.tradingmatchingenginejava.domain;

import com.github.lisama569.tradingmatchingenginejava.exception.TradingException;

/**
 * 单只标的的持仓。单位：股。
 * qty 总持仓，frozenQty 卖出挂单冻结量；可用 = qty - frozenQty。
 *
 * 不变量（所有修改均需持有账户锁）：
 *   qty >= 0；frozenQty >= 0；frozenQty <= qty。
 * 一旦会破坏不变量直接抛 SYSTEM_ERROR，属于内部记账错误，绝不静默放行。
 */
public final class Position {

    private long qty;
    private long frozenQty;

    public Position() {
        this(0L);
    }

    public Position(long initialQty) {
        if (initialQty < 0) {
            throw new IllegalArgumentException("初始持仓不能为负");
        }
        this.qty = initialQty;
        this.frozenQty = 0L;
    }

    public long getQty() { return qty; }
    public long getFrozenQty() { return frozenQty; }
    public long getAvailableQty() { return qty - frozenQty; }

    /** 卖出挂单冻结。可用不足由调用方在风控阶段拦截，这里做最终防线。 */
    public void freeze(long amount) {
        if (amount < 0 || amount > getAvailableQty()) {
            throw new TradingException(RejectReason.SYSTEM_ERROR,
                    "持仓冻结非法：amount=" + amount + ", available=" + getAvailableQty());
        }
        frozenQty += amount;
    }

    /** 撤单释放冻结。 */
    public void unfreeze(long amount) {
        if (amount < 0 || amount > frozenQty) {
            throw new TradingException(RejectReason.SYSTEM_ERROR,
                    "持仓解冻非法：amount=" + amount + ", frozen=" + frozenQty);
        }
        frozenQty -= amount;
    }

    /** 卖方成交：从冻结量和总持仓中同时扣减（卖出冻结在下单时已完成）。 */
    public void applySellFill(long amount) {
        if (amount < 0 || amount > frozenQty || amount > qty) {
            throw new TradingException(RejectReason.SYSTEM_ERROR,
                    "卖方成交扣减持仓非法：amount=" + amount + ", qty=" + qty + ", frozen=" + frozenQty);
        }
        frozenQty -= amount;
        qty -= amount;
    }

    /** 买方成交：总持仓增加（买入资金在下单冻结/市价校验时已保证）。 */
    public void applyBuyFill(long amount) {
        if (amount < 0) {
            throw new TradingException(RejectReason.SYSTEM_ERROR, "买方成交数量非法：" + amount);
        }
        qty += amount;
    }
}
