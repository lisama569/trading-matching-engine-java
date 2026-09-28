package com.github.lisama569.tradingmatchingenginejava.dto;

import java.util.Map;

/**
 * 账户快照：资金 + 各标的持仓。
 * positions 的每个 long[] 固定为 [总持仓, 冻结, 可用]。
 */
public class AccountSnapshot {

    private String accountId;
    private long cash;
    private long frozenCash;
    private long availableCash;
    private Map<String, long[]> positions;

    public AccountSnapshot() {
    }

    public AccountSnapshot(String accountId, long cash, long frozenCash, long availableCash,
                           Map<String, long[]> positions) {
        this.accountId = accountId;
        this.cash = cash;
        this.frozenCash = frozenCash;
        this.availableCash = availableCash;
        this.positions = positions;
    }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public long getCash() { return cash; }
    public void setCash(long cash) { this.cash = cash; }
    public long getFrozenCash() { return frozenCash; }
    public void setFrozenCash(long frozenCash) { this.frozenCash = frozenCash; }
    public long getAvailableCash() { return availableCash; }
    public void setAvailableCash(long availableCash) { this.availableCash = availableCash; }
    public Map<String, long[]> getPositions() { return positions; }
    public void setPositions(Map<String, long[]> positions) { this.positions = positions; }
}
