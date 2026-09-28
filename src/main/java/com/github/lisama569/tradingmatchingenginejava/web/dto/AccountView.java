package com.github.lisama569.tradingmatchingenginejava.web.dto;

import java.util.List;

/** 账户状态视图：现金、冻结现金、可用现金与各标的持仓。 */
public class AccountView {

    private long accountId;
    private String name;
    private long cash;
    private long frozenCash;
    private long availableCash;
    private List<PositionView> positions;

    public static class PositionView {
        private String symbol;
        private long qty;
        private long frozenQty;
        private long availableQty;

        public PositionView(String symbol, long qty, long frozenQty, long availableQty) {
            this.symbol = symbol;
            this.qty = qty;
            this.frozenQty = frozenQty;
            this.availableQty = availableQty;
        }

        public String getSymbol() { return symbol; }
        public long getQty() { return qty; }
        public long getFrozenQty() { return frozenQty; }
        public long getAvailableQty() { return availableQty; }
    }

    public long getAccountId() { return accountId; }
    public void setAccountId(long accountId) { this.accountId = accountId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public long getCash() { return cash; }
    public void setCash(long cash) { this.cash = cash; }
    public long getFrozenCash() { return frozenCash; }
    public void setFrozenCash(long frozenCash) { this.frozenCash = frozenCash; }
    public long getAvailableCash() { return availableCash; }
    public void setAvailableCash(long availableCash) { this.availableCash = availableCash; }
    public List<PositionView> getPositions() { return positions; }
    public void setPositions(List<PositionView> positions) { this.positions = positions; }
}
