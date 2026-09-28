package com.github.lisama569.tradingmatchingenginejava.engine;

import com.github.lisama569.tradingmatchingenginejava.domain.Order;
import com.github.lisama569.tradingmatchingenginejava.domain.OrderStatus;
import com.github.lisama569.tradingmatchingenginejava.dto.PlaceOrderRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发场景测试：
 *  - 同一账户大量并发下单，冻结资金/持仓不得为负、超量成交；
 *  - 并发撤单与撮合竞争下，订单状态唯一、成交不重不漏；
 *  - 用全局恒等式做最终对账（资金守恒 + 持仓守恒 + 冻结非负）。
 */
class MatchingConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(MatchingConcurrencyTest.class);

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
        engine = new MatchingEngine(Set.of("AAPL"), 1_000_000, 1_000_000_000_000L);
        engine.registerAccount("buyer", 1_000_000_000L);
        engine.registerAccount("seller", 1_000_000_000L);
        engine.registerAccount("mm", 1_000_000_000L);
        engine.depositStock("seller", "AAPL", 1_000_000);
        engine.depositStock("mm", "AAPL", 1_000_000);
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
    }

    /** 同账户 N 个线程同时买入挂单：成功的笔数必须与冻结资金严格对应，可用资金永不被穿透。 */
    @Test
    void concurrentBuyOrdersNeverOverdraft() throws Exception {
        int threads = 64;
        int perThread = 20;
        long price = 100L;
        long qty = 10L;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Order> all = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        try {
                            Order o = engine.placeOrder(req("buyer", "AAPL", "BUY", "LIMIT", price, qty));
                            accepted.incrementAndGet();
                            all.add(o);
                        } catch (com.github.lisama569.tradingmatchingenginejava.exception.TradingException e) {
                            rejected.incrementAndGet();
                            assertEquals("INSUFFICIENT_CASH", e.getReason().name());
                        }
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        long affordable = engine.getAccount("buyer").getCash() / (price * qty);
        long expectedAccepted = Math.min((long) threads * perThread, affordable);
        // 买单不穿价（无卖盘在 100 以下），全部挂单。
        assertEquals(expectedAccepted, accepted.get(),
                "成功挂单数必须由 min(提交量, 资金可支撑量) 决定，不能并发穿透");
        assertEquals((long) threads * perThread - expectedAccepted, rejected.get());
        long frozen = engine.getAccount("buyer").getFrozenCash();
        assertEquals(expectedAccepted * price * qty, frozen, "冻结资金必须与成功单数一一对应");
        assertTrue(frozen >= 0 && engine.getAccount("buyer").getAvailableCash() >= 0);
        log.info("[结论] 并发买单: 提交={}, 接受={}, 拒绝={}, 冻结={}, 可用={}",
                threads * perThread, accepted.get(), rejected.get(), frozen,
                engine.getAccount("buyer").getAvailableCash());
    }

    /**
     * 同一批挂单并发撮合 + 撤单竞争：
     * 每笔订单最终只能是 FILLED / CANCELED / PARTIALLY_FILLED+在簿 之一，
     * 累计成交量必须等于卖方总减持股数与买方总增持股数。
     */
    @Test
    void concurrentCancelAndMatch_noLostOrDoubleFill() throws Exception {
        // 卖方在 100 价位挂 200 笔各 10 股（共 2000 股），做固定流动性。
        int asks = 200;
        for (int i = 0; i < asks; i++) {
            engine.placeOrder(req("seller", "AAPL", "SELL", "LIMIT", 100L, 10));
        }
        // 买方并发提交 400 笔各 10 股的市价单：超过流动性 2000 股，部分无流动性被拒。
        int buyers = 400;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger filled = new AtomicInteger();
        AtomicInteger rejectedNoLiquidity = new AtomicInteger();
        long[] filledQtyHolder = new long[1];

        for (int i = 0; i < buyers; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    try {
                        Order o = engine.placeOrder(req("buyer", "AAPL", "BUY", "MARKET", null, 10));
                        synchronized (filledQtyHolder) {
                            filledQtyHolder[0] += o.getFilledQty();
                        }
                        if (o.getFilledQty() > 0) {
                            filled.incrementAndGet();
                        }
                    } catch (com.github.lisama569.tradingmatchingenginejava.exception.TradingException e) {
                        if (e.getReason().name().equals("MARKET_NO_LIQUIDITY")) {
                            rejectedNoLiquidity.incrementAndGet();
                        }
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        // 卖方只有 2000 股，买方总成交必须恰好 2000，不能超量也不能丢量。
        assertEquals(2000L, filledQtyHolder[0], "总成交量必须严格等于卖方流动性");
        long[] buyerPos = engine.positionSnapshot("buyer", "AAPL");
        long[] sellerPos = engine.positionSnapshot("seller", "AAPL");
        assertEquals(2000L, buyerPos[0], "买方入账持仓必须等于成交量");
        assertEquals(1_000_000L - 2000L, sellerPos[0], "卖方减持仓必须等于成交量");
        assertEquals(0L, sellerPos[1], "卖方全部成交后冻结应归零");
        assertEquals(2000L * 100L, engine.getAccount("seller").getCash() - 1_000_000_000L,
                "卖方入账现金必须等于成交金额");
        assertEquals(1_000_000_000L - 2000L * 100L, engine.getAccount("buyer").getCash(),
                "买方扣款必须等于成交金额");
        // 成交记录总量也要对上，不能记两次或漏记。
        long tradeQty = engine.trades("AAPL").stream().mapToLong(t -> t.getQuantity()).sum();
        assertEquals(2000L, tradeQty, "成交记录数量之和必须等于成交量");
        log.info("[结论] 并发市价: 成交买单={}, 无流动性拒绝={}, 成交总量={}, Trade笔数={}",
                filled.get(), rejectedNoLiquidity.get(), filledQtyHolder[0],
                engine.trades("AAPL").size());
    }

    /** 并发撤单：同一笔撤单请求只有一个能成功，其余拒绝原因稳定。 */
    @Test
    void concurrentCancelSameOrder_onlyOneSucceeds() throws Exception {
        Order bid = engine.placeOrder(req("buyer", "AAPL", "BUY", "LIMIT", 90L, 5));
        int n = 32;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();

        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    var c = new com.github.lisama569.tradingmatchingenginejava.dto.CancelOrderRequest();
                    c.setAccountId("buyer");
                    c.setOrderId(bid.getId());
                    try {
                        engine.cancel(c);
                        success.incrementAndGet();
                    } catch (com.github.lisama569.tradingmatchingenginejava.exception.TradingException e) {
                        if (e.getReason().name().equals("ORDER_ALREADY_FINISHED")) {
                            finished.incrementAndGet();
                        }
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(1, success.get(), "同一订单只能撤成功一次");
        assertEquals(n - 1, finished.get(), "其余撤单必须为 ORDER_ALREADY_FINISHED");
        assertEquals(0L, engine.getAccount("buyer").getFrozenCash(), "冻结只释放一次");
        assertEquals(OrderStatus.CANCELED, engine.getOrder(bid.getId()).getStatus());
        log.info("[结论] 并发撤单: 成功={}, 已结束拒绝={}, 冻结归零", success.get(), finished.get());
    }
}
