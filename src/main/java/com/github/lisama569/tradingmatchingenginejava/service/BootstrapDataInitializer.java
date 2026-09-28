package com.github.lisama569.tradingmatchingenginejava.service;

import com.github.lisama569.tradingmatchingenginejava.engine.MatchingEngine;
import com.github.lisama569.tradingmatchingenginejava.model.Account;
import com.github.lisama569.tradingmatchingenginejava.model.InstrumentSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 本地内存数据初始化（应用启动即运行）。
 * <p>预置一个标的 BTC（价格带 100.00~200.00，即 10000~20000 分）和三个账户：
 * <ul>
 *     <li>账户 1：现金 1,000,000 分（10,000 元）；</li>
 *     <li>账户 2：现金 500,000 分 + BTC 100 股；</li>
 *     <li>账户 3：现金 100,000 分。</li>
 * </ul>
 * 全部数据只在内存中，重启即重置。
 */
@Component
public class BootstrapDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapDataInitializer.class);

    private final MatchingEngine engine;
    private final AccountService accountService;

    public BootstrapDataInitializer(MatchingEngine engine, AccountService accountService) {
        this.engine = engine;
        this.accountService = accountService;
    }

    @Override
    public void run(ApplicationArguments args) {
        // symbol, name, lotSize, tickSize, lowerLimit, upperLimit, maxQty, maxNotional, marketMaxPrice
        engine.registerInstrument(new InstrumentSpec(
                "BTC", "Bitcoin Demo", 1, 5, 10000, 20000, 10_000, 1_000_000_000L, 20000));
        engine.registerInstrument(new InstrumentSpec(
                "ETH", "Ether Demo", 10, 1, 1000, 5000, 100_000, 1_000_000_000L, 5000));

        Account a1 = new Account(1, "alice", 1_000_000);
        Account a2 = new Account(2, "bob", 500_000);
        a2.depositPosition("BTC", 100);
        a2.depositPosition("ETH", 500);
        Account a3 = new Account(3, "carol", 100_000);
        accountService.register(a1);
        accountService.register(a2);
        accountService.register(a3);

        log.info("内存数据初始化完成: instruments=[BTC,ETH], accounts=[1:alice, 2:bob, 3:carol]");
    }
}
