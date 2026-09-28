package com.github.lisama569.tradingmatchingenginejava.tests;

import com.github.lisama569.tradingmatchingenginejava.engine.MatchingEngine;
import com.github.lisama569.tradingmatchingenginejava.model.Account;
import com.github.lisama569.tradingmatchingenginejava.model.InstrumentSpec;
import com.github.lisama569.tradingmatchingenginejava.service.AccountService;
import com.github.lisama569.tradingmatchingenginejava.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 撮合性能不退化测试：
 * TreeMap 定价位 + FIFO 队列决定了单笔撮合只与价位数有关，与历史挂单总量无关。
 * 比较簿在 1 千与 20 万挂单规模下，1 万笔小单的平均撮合耗时不应有数量级退化。
 */
class MatchingPerformanceTest {

    private static final Logger log = LoggerFactory.getLogger(MatchingPerformanceTest.class);

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
        // 一个极富的买方账户做买单，卖方账户持有巨量 BTC
        accountService.register(new Account(1, "market-maker-buyer", Long.MAX_VALUE / 4));
        Account seller = new Account(2, "seller", 0);
        seller.depositPosition("BTC", 1_000_000_000);
        accountService.register(seller);
    }

    @Test
    @DisplayName("挂单量从 1 千涨到 20 万：单笔平均撮合耗时不出现数量级退化")
    void matchingTimeStaysFlatAsBookGrows() {
        // JIT 预热：先完整跑一轮小簿，不计时，保证两次正式测量都在热态
        benchmarkBookBuildAndCross(500);
        double smallBookAvg = benchmarkBookBuildAndCross(1_000);
        double largeBookAvg = benchmarkBookBuildAndCross(200_000);
        log.info("[判定依据] 簿规模 1k 时单笔平均 {} ns；200k 时 {} ns（倍率 {}）",
                String.format("%.0f", smallBookAvg), String.format("%.0f", largeBookAvg),
                String.format("%.2f", largeBookAvg / smallBookAvg));
        // 大簿平均耗时不允许超过小簿的 5 倍（留足 GC/抖动余量），且绝对值足够快
        assertTrue(largeBookAvg < 200_000, "20 万挂单下单笔撮合平均应低于 0.2ms");
        assertTrue(largeBookAvg < smallBookAvg * 5,
                "撮合耗时不应随历史挂单总量出现数量级退化");
    }

    /**
     * 先在卖盘挂 restingCount 笔限价单（分布在约 400 个价位上），
     * 再提交 crosses 笔主动买单（每笔买 1 股并立即撤掉剩余——这里用价格只吃最优价后挂单，
     * 为保持纯粹我们改为：每笔买单价格等于最优卖价、数量 1，全部即时成交）。
     * 返回每笔交叉成交的平均纳秒数。
     */
    private double benchmarkBookBuildAndCross(int restingCount) {
        // 造量：restingCount 笔卖单，价格在 12000~14000 循环（tick=5）
        for (int i = 0; i < restingCount; i++) {
            long price = 12000 + (i % 400) * 5L;
            orderService.submit(2, "BTC", "SELL", "LIMIT", price, 1);
        }

        int crosses = 10_000;
        long start = System.nanoTime();
        for (int i = 0; i < crosses; i++) {
            // 始终以最高卖价上限吃单，确保命中最优价并成交 1 股
            orderService.submit(1, "BTC", "BUY", "LIMIT", 14000L, 1);
        }
        long elapsed = System.nanoTime() - start;
        return (double) elapsed / crosses;
    }
}
