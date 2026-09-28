package com.github.lisama569.tradingmatchingenginejava.web.dto;

import java.util.List;

/** 盘口视图。levels 元素为 [价格(分), 该价位剩余总量, 该价位活跃量]。 */
public class BookView {

    private String symbol;
    private List<long[]> bids;
    private List<long[]> asks;

    public BookView(String symbol, List<long[]> bids, List<long[]> asks) {
        this.symbol = symbol;
        this.bids = bids;
        this.asks = asks;
    }

    public String getSymbol() { return symbol; }
    public List<long[]> getBids() { return bids; }
    public List<long[]> getAsks() { return asks; }
}
