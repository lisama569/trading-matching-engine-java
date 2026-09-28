package com.github.lisama569.tradingmatchingenginejava.engine;

import com.github.lisama569.tradingmatchingenginejava.domain.Order;
import com.github.lisama569.tradingmatchingenginejava.dto.PlaceOrderRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 性能基准：验证挂单量从 1 万扩大到 10 万时，单笔市价吃单的撮合耗时基本不随挂单总量增长
 * （订单簿 TreeMap 按价位 O(log n)、价位内惰性清理，撤单不做 O(n) 摘除）。
 */
class MatchingPerformanceTest {

    private static final Logger log = LoggerFactory.getLogger(MatchingPerformanceTest.class);

    private MatchingEngine engine;

    private PlaceOrderRequest req(String account, String symbol, String side, String type,
                                  Long price, long qty) {
        PlaceOrderRequest r = new PlaceOrderRequest();
        r.setAccountId(account); r.setSymbol(symbol); r.setSide(side);
        r.setType(type); r.setPrice(price); r.setQuantity(qty);
        return r;
    }

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine(Set.of("AAPL"), 10_000_000, 1_000_000_000_000_000L);
        engine.registerAccount("maker", 1_000_000_000_000_000L);
        engine.registerAccount("taker", 1_000_000_000_000_000L);
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
    }

    private long seedSellBook(int levels, int ordersPerLevel) {
        engine.depositStock("maker", "AAPL", (long) levels * ordersPerLevel * 10);
        long start = System.nanoTime();
        for (int p = 100; p < 100 + levels; p++) {
            for (int i = 0; i < ordersPerLevel; i++) {
                engine.placeOrder(req("maker", "AAPL", "SELL", "LIMIT", (long) p, 10));
            }
        }
        return System.nanoTime() - start;
    }

    @Test
    void matchLatencyDoesNotGrowWithBookDepth() {
        // 小簿：100 价位 × 100 笔 = 1 万挂单；大簿：1000 价位 × 100 笔 = 10 万挂单。
        seedSellBook(100, 100);
        long smallBookMatch = timeOneMarketBuy();

        long seed100k = seedSellBook(900, 100);
        long largeBookMatch = timeOneMarketBuy();

        // 市价买单只吃最优价 100 股（10 笔 x10），与簿深无关；
        // 允许抖动，放宽到大簿撮合不超过小簿的 10 倍且绝对值 < 200ms。
        log.info("[性能] 小簿(1万)吃单={} ms, 大簿(10万)吃单={} ms, 追加挂单9万耗时={} ms",
                smallBookMatch / 1_000_000, largeBookMatch / 1_000_000, seed100k / 1_000_000);
        assertTrue(largeBookMatch < 200_000_000L,
                "10万挂单下单笔吃单必须 < 200ms，实际 " + largeBookMatch / 1_000_000 + "ms");
        assertTrue(largeBookMatch < Math.max(5_000_000L, smallBookMatch * 10L),
                "撮合耗时不应随挂单量线性退化");
    }

    private long timeOneMarketBuy() {
        long start = System.nanoTime();
        Order o = engine.placeOrder(req("taker", "AAPL", "BUY", "MARKET", null, 100));
        long elapsed = System.nanoTime() - start;
        assertEquals(100, o.getFilledQty(), "市价单应吃到最优价全部100股");
        return elapsed;
    }

    @Test
    void cancelHundredThousandOrdersIsBounded() {
        engine.depositStock("maker", "AAPL", 1_000_000L);
        int n = 100_000;
        long[] ids = new long[n];
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            ids[i] = engine.placeOrder(
                    req("maker", "AAPL", "SELL", "LIMIT", 100L, 10)).getId();
        }
        long placeElapsed = System.nanoTime() - t0;

        long t1 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            var c = new com.github.lisama569.tradingmatchingenginejava.dto.CancelOrderRequest();
            c.setAccountId("maker");
            c.setOrderId(ids[i]);
            engine.cancel(c);
        }
        long cancelElapsed = System.nanoTime() - t1;

        // 惰性撤单是簿内线性查找……我们靠价位桶缩小范围；10万撤单应在可接受时间内完成。
        log.info("[性能] 挂单10万: 下单总耗时={} ms (avg {} us), 撤单总耗时={} ms (avg {} us)",
                placeElapsed / 1_000_000, placeElapsed / n / 1_000,
                cancelElapsed / 1_000_000, cancelElapsed / n / 1_000);
        assertEquals(0L, engine.positionSnapshot("maker", "AAPL")[1], "全部撤单后冻结归零");
        assertTrue(cancelElapsed < 30_000_000_000L);
    }
}
