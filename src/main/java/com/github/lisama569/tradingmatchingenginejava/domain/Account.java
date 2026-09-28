package com.github.lisama569.tradingmatchingenginejava.domain;

import com.github.lisama569.tradingmatchingenginejava.exception.TradingException;

/**
 * 资金账户。单位：分。
 * cash 总资金，frozenCash 买入挂单冻结资金；可用 = cash - frozenCash。
 *
 * 不变量（所有修改均需持有账户锁）：
 *   cash >= 0；frozenCash >= 0；frozenCash <= cash。
 * 市价买入没有预先冻结，成交时先检查可用资金再扣款，资金不足即停止撮合，
 * 因此不会出现成交了却没钱扣的情况。
 */
public final class Account {

    private final String accountId;
    private long cash;
    private long frozenCash;

    public Account(String accountId, long initialCash) {
        if (initialCash < 0) {
            throw new IllegalArgumentException("初始资金不能为负");
        }
        this.accountId = accountId;
        this.cash = initialCash;
        this.frozenCash = 0L;
    }

    public String getAccountId() { return accountId; }
    public long getCash() { return cash; }
    public long getFrozenCash() { return frozenCash; }
    public long getAvailableCash() { return cash - frozenCash; }

    /** 限价买入挂单冻结。可用不足由调用方在风控阶段拦截，这里做最终防线。 */
    public void freezeCash(long amount) {
        if (amount < 0 || amount > getAvailableCash()) {
            throw new TradingException(RejectReason.SYSTEM_ERROR,
                    "资金冻结非法：amount=" + amount + ", available=" + getAvailableCash());
        }
        frozenCash += amount;
    }

    /** 撤单释放冻结资金。 */
    public void unfreezeCash(long amount) {
        if (amount < 0 || amount > frozenCash) {
            throw new TradingException(RejectReason.SYSTEM_ERROR,
                    "资金解冻非法：amount=" + amount + ", frozen=" + frozenCash);
        }
        frozenCash -= amount;
    }

    /**
     * 买方成交结算（原子完成两步，调用方需持有账户锁）：
     *   1) 从冻结资金释放该笔成交对应的挂单价预算 budgetToUnfreeze
     *      （限价单 = 挂单价×成交量；市价单 = 0）；
     *   2) 按实际成交额 notional（= 成交价×成交量）扣减总资金。
     * 若成交价低于挂单价，差价（价格改善）自动回到可用资金；
     * 限价单 budgetToUnfreeze >= notional，故释放后可用资金必然充足。
     */
    public void settleBuy(long notional, long budgetToUnfreeze) {
        if (notional < 0 || budgetToUnfreeze < 0 || budgetToUnfreeze > frozenCash) {
            throw new TradingException(RejectReason.SYSTEM_ERROR,
                    "买方结算参数非法：notional=" + notional + ", budget=" + budgetToUnfreeze
                            + ", frozen=" + frozenCash);
        }
        frozenCash -= budgetToUnfreeze;
        if (notional > getAvailableCash()) {
            // 市价单由引擎逐笔保证资金充足；走到这里说明记账出了内部错误。
            throw new TradingException(RejectReason.SYSTEM_ERROR,
                    "买方成交资金不足：notional=" + notional + ", available=" + getAvailableCash());
        }
        cash -= notional;
    }

    /** 卖方成交结算：卖出所得入账。 */
    public void applySellFill(long notional) {
        if (notional < 0) {
            throw new TradingException(RejectReason.SYSTEM_ERROR, "卖方成交金额非法：" + notional);
        }
        cash += notional;
    }
}
