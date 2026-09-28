package com.github.lisama569.tradingmatchingenginejava.service;

import com.github.lisama569.tradingmatchingenginejava.engine.MatchingEngine;
import com.github.lisama569.tradingmatchingenginejava.model.Side;
import com.github.lisama569.tradingmatchingenginejava.model.Trade;
import com.github.lisama569.tradingmatchingenginejava.web.dto.BookView;
import org.springframework.stereotype.Service;

import java.util.List;

/** 行情查询：盘口深度与最近成交。查询同样走标的锁，保证看到的是某一刻自洽的快照。 */
@Service
public class MarketDataService {

    private static final int DEFAULT_DEPTH = 10;
    private static final int DEFAULT_TRADES = 50;

    private final MatchingEngine engine;

    public MarketDataService(MatchingEngine engine) {
        this.engine = engine;
    }

    public BookView getBook(String symbol, Integer depth) {
        int maxLevels = depth == null || depth <= 0 ? DEFAULT_DEPTH : Math.min(depth, 100);
        List<long[]> bids = engine.getBookLevels(symbol, Side.BUY, maxLevels);
        List<long[]> asks = engine.getBookLevels(symbol, Side.SELL, maxLevels);
        return new BookView(symbol, bids, asks);
    }

    public List<Trade> getRecentTrades(String symbol, Integer limit) {
        int max = limit == null || limit <= 0 ? DEFAULT_TRADES : Math.min(limit, 500);
        return engine.getRecentTrades(symbol, max);
    }
}
