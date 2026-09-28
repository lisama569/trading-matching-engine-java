package com.github.lisama569.tradingmatchingenginejava.tests;

import com.github.lisama569.tradingmatchingenginejava.common.TradingException;
import com.github.lisama569.tradingmatchingenginejava.engine.MatchingEngine;
import com.github.lisama569.tradingmatchingenginejava.model.Account;
import com.github.lisama569.tradingmatchingenginejava.model.InstrumentSpec;
import com.github.lisama569.tradingmatchingenginejava.model.Order;
import com.github.lisama569.tradingmatchingenginejava.model.OrderStatus;
import com.github.lisama569.tradingmatchingenginejava.model.RejectCode;
import com.github.lisama569.tradingmatchingenginejava.model.Side;
import com.github.lisama569.tradingmatchingenginejava.service.AccountService;
import com.github.lisama569.tradingmatchingenginejava.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发一致性测试。
 * 断言目标：
 * <ol>
 *     <li>同一账户并发下单，冻结/可用永不为负，接受量与拒绝量之和 == 请求总数（不丢单）；</li>
 *     <li>同一卖盘并发被吃，总成交量恰好等于挂单量（不超量、不重复成交）；</li>
 *     <li>全局资金守恒：系统内现金总量不变；总持仓不变；</li>
 *     <li>同一订单并发撤单恰好一次成功，且在死锁检测超时内全部返回（不死锁）。</li>
 * </ol>
 */
class ConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTest.class);

    private AccountService accountService;
    private MatchingEngine engine;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        accountService = new AccountService();
        engine = new MatchingEngine(accountService);
        orderService = new OrderService(engine, accountService);
        engine.registerInstrument(new InstrumentSpec(
                "BTC", "Bitcoin Demo", 1, 5, 10000, 20000, 10_000_000, Long.MAX_VALUE, 20000));
    }

    @Test
    @DisplayName("同一账户并发买入：成功单数 * 金额 == 冻结额，拒绝原因全为 INSUFFICIENT_CASH")
    void concurrentBuysSameAccountNeverOverFreeze() {
        Account account = new Account(1, "concurrent-buyer", 1_050_000);
        accountService.register(account);
        int threads = 64;
        // 每单需要 10500*10=105000；资金恰好支持 10 单，第 11 单起必拒
        int expectedAcceptedMax = 10;

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(30), () -> {
            runConcurrently(threads, i ->
                    orderService.submit(1, "BTC", "BUY", "LIMIT", 10500L, 10).order());
        });

        long frozen = account.getFrozenCash();
        long available = account.availableCash();
        log.info("[判定依据] 64 笔并发买单后: cash={}, frozen={}, available={}, 接受={}, 拒绝={}",
                account.getCash(), frozen, available, acceptedCount.get(), rejectedCodes.size());
        assertTrue(frozen >= 0 && available >= 0, "冻结与可用资金都不能为负");
        assertEquals(0, available, "资金按单笔冻结额整除，打满后可用应为 0");
        assertEquals(expectedAcceptedMax, acceptedCount.get(), "恰好 10 笔被接受（成交或挂单）");
        assertEquals(expectedAcceptedMax * 105_000L, frozen, "接受的每一笔都全额冻结");
        assertEquals(threads - expectedAcceptedMax, countRejects(RejectCode.INSUFFICIENT_CASH));
        assertEquals(threads, acceptedCount.get() + rejectedCodes.size(), "所有请求必须有确定结论，不丢单");
    }

    @Test
    @DisplayName("多账户同时吃同一卖盘：总成交量恰好等于挂单量，且全局现金/持仓守恒")
    void concurrentTakersNeverOverfillMaker() {
        Account seller = new Account(1, "seller", 0);
        seller.depositPosition("BTC", 500);
        accountService.register(seller);
        // 卖方挂 500 股
        orderService.submit(1, "BTC", "SELL", "LIMIT", 10500L, 500);

        int buyerCount = 50;
        for (int i = 2; i < 2 + buyerCount; i++) {
            accountService.register(new Account(i, "buyer-" + i, 10_000_000));
        }
        long totalCashBefore = totalCash();
        long totalBtcBefore = totalBtc();

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(30), () ->
                runConcurrently(buyerCount, i ->
                        orderService.submit(2L + i, "BTC", "BUY", "LIMIT", 10500L, 20).order()));

        long filledQty = acceptedOrders.stream()
                .filter(o -> o.getSide() == Side.BUY)
                .mapToLong(Order::getFilledQty).sum();
        log.info("[判定依据] 50 笔 x20 并发买单，总成交量={}（期望恰好 500）", filledQty);
        assertEquals(500, filledQty, "总成交不能超过 500（超量成交），也不能少于 500（丢成交）");

        // 通过盘口确认卖盘清空
        assertTrue(engine.getBookLevels("BTC", Side.SELL, 10).isEmpty(), "500 股应被全部吃掉");

        assertEquals(totalCashBefore, totalCash(), "系统内现金总量必须守恒");
        assertEquals(totalBtcBefore, totalBtc(), "系统内 BTC 总量必须守恒");

        // 每个买方：现金扣减+冻结之和必须等于总挂单额；持仓 == 已成交量
        for (int i = 2; i < 2 + buyerCount; i++) {
            Account b = accountService.get(i);
            long qty = b.positionOf("BTC").getQty();
            assertEquals(10_000_000 - qty * 10500L, b.getCash(), "买方现金扣减必须与成交持仓对得上");
            // 成交的部分不占冻结；未成交而挂入簿中的部分继续全额冻结，二者加总等于下单额
            assertEquals((20 - qty) * 10500L, b.getFrozenCash(), "未成交部分必须继续冻结");
            assertEquals(qty, b.positionOf("BTC").getQty());
        }
        assertEquals(500 * 10500L, seller.getCash());
        assertEquals(0, seller.positionOf("BTC").getQty());
        log.info("[判定依据] 全局现金={} 守恒、BTC={} 守恒、逐账户账实相符", totalCash(), totalBtc());
    }

    @Test
    @DisplayName("同一订单并发撤单 + 成交竞争：恰好一种终态，冻结最终为 0")
    void concurrentCancelOnlyOneSucceeds() {
        Account buyer = new Account(1, "buyer", 1_000_000);
        accountService.register(buyer);
        Order resting = orderService.submit(1, "BTC", "BUY", "LIMIT", 10100L, 10).order();

        // 撤单在没有卖盘的情况下：20 个线程同时撤，恰好 1 次成功
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(30), () ->
                runConcurrently(20, i -> orderService.cancel(1, resting.getId())));
        assertEquals(OrderStatus.CANCELED, resting.getStatus());
        assertEquals(1, acceptedCount.get(), "并发撤单恰好一次成功");
        assertEquals(19, countRejects(RejectCode.ORDER_NOT_ACTIVE));
        assertEquals(0, buyer.getFrozenCash(), "撤单成功后冻结必须全部释放且只释放一次");
        log.info("[判定依据] 20 个并发撤单：1 次成功、19 次 ORDER_NOT_ACTIVE，冻结=0");
    }

    @Test
    @DisplayName("撤单与成交竞争：不管谁赢，账务都自洽")
    void cancelVersusTradeRace() throws Exception {
        Account seller = new Account(1, "seller", 0);
        seller.depositPosition("BTC", 100_000);
        accountService.register(seller);
        Account buyer = new Account(2, "buyer", 100_000_000);
        accountService.register(buyer);

        int rounds = 200;
        int qtyPerRound = 10;
        for (int round = 0; round < rounds; round++) {
            Order resting = orderService.submit(1, "BTC", "SELL", "LIMIT", 10500L, qtyPerRound).order();
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<String> outcomes = Collections.synchronizedList(new ArrayList<>());
            Order[] buyerOrder = new Order[1];
            pool.submit(() -> {
                await(start);
                try {
                    orderService.cancel(1, resting.getId());
                    outcomes.add("CANCELED");
                } catch (TradingException e) {
                    outcomes.add("REJECT:" + e.getCode());
                }
            });
            pool.submit(() -> {
                await(start);
                try {
                    Order o = orderService.submit(2, "BTC", "BUY", "LIMIT", 10500L, qtyPerRound).order();
                    buyerOrder[0] = o;
                    outcomes.add(o.getFilledQty() == qtyPerRound ? "FILLED" : "RESTING");
                } catch (TradingException e) {
                    outcomes.add("REJECT:" + e.getCode());
                }
            });
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "第 " + round + " 轮出现卡死（可能死锁）");

            // 终态互斥：卖单要么 FILLED 要么 CANCELED，没有第三种状态。
            boolean filled = resting.getStatus() == OrderStatus.FILLED;
            boolean canceled = resting.getStatus() == OrderStatus.CANCELED;
            assertTrue(filled ^ canceled,
                    "第 " + round + " 轮卖单终态必须互斥，status=" + resting.getStatus());
            if (filled) {
                assertTrue(outcomes.contains("FILLED"), "卖方成交时买方结果应为 FILLED: " + outcomes);
            } else {
                assertTrue(outcomes.contains("CANCELED"), "卖方撤销时必须有撤单成功结果: " + outcomes);
                // 买方的限价买单可能挂在簿里；撤掉，避免跨轮与下一轮卖单提前撮合干扰隔离性
                if (buyerOrder[0] != null && buyerOrder[0].isActive()) {
                    orderService.cancel(2, buyerOrder[0].getId());
                }
            }
        }

        // 每轮要么成交 10 股、要么撤单（买方挂单也同步撤掉），因此最终只有成交改变持仓
        long sellerBtc = seller.positionOf("BTC").getQty();
        long buyerBtc = buyer.positionOf("BTC").getQty();
        long sellerFrozen = seller.positionOf("BTC").getFrozenQty();
        long buyerFrozenCash = buyer.getFrozenCash();
        long tradedQty = buyerBtc;
        log.info("[判定依据] {} 轮竞争后: 成交量={}, sellerBtc={}, sellerFrozen={}, buyerFrozenCash={}",
                rounds, tradedQty, sellerBtc, sellerFrozen, buyerFrozenCash);
        assertEquals(0, sellerFrozen, "撤单轮卖单冻结已释放，成交轮冻结已交割");
        assertEquals(0, buyerFrozenCash, "撤单轮买方挂单已清理，冻结已释放");
        assertEquals(100_000L - tradedQty, sellerBtc, "卖方持仓 = 初始 - 总成交量");
        assertEquals(tradedQty * 10500L, 100_000_000 - buyer.getCash(), "现金流转必须与成交一致");
    }

    // ---------------- 辅助 ----------------

    private final AtomicInteger acceptedCount = new AtomicInteger();
    private final List<RejectCode> rejectedCodes = Collections.synchronizedList(new ArrayList<>());
    private final List<Order> acceptedOrders = Collections.synchronizedList(new ArrayList<>());

    @FunctionalInterface
    interface ClientAction {
        void run(int index);
    }

    @FunctionalInterface
    interface ClientCall {
        Order run(int index);
    }

    private void runConcurrently(int n, ClientCall call) throws InterruptedException {
        acceptedCount.set(0);
        rejectedCodes.clear();
        acceptedOrders.clear();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(n, 32));
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            int idx = i;
            pool.submit(() -> {
                await(start);
                try {
                    Order order = call.run(idx);
                    acceptedCount.incrementAndGet();
                    acceptedOrders.add(order);
                } catch (TradingException e) {
                    rejectedCodes.add(e.getCode());
                } catch (RuntimeException e) {
                    log.error("并发动作出现非业务异常", e);
                    throw e;
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发任务未在超时前全部完成（疑似死锁）");
        pool.shutdown();
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private int countRejects(RejectCode code) {
        return (int) rejectedCodes.stream().filter(code::equals).count();
    }

    private long totalCash() {
        return accountService.all().stream().mapToLong(Account::getCash).sum();
    }

    private long totalBtc() {
        return accountService.all().stream().mapToLong(a -> a.positionOf("BTC").getQty()).sum();
    }
}
