package com.github.lisama569.tradingmatchingenginejava.config;

import com.github.lisama569.tradingmatchingenginejava.engine.MatchingEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时注入演示账户与初始资金/持仓，方便本地直接联调。
 * 单位：现金为分，持仓为股。
 */
@Component
public class SeedDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDataInitializer.class);

    private final MatchingEngine engine;

    public SeedDataInitializer(MatchingEngine engine) {
        this.engine = engine;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 三个演示账户：A 偏买方现金多，B 偏卖方持仓多，C 资金较少用于限额/不足场景。
        engine.registerAccount("A", 1_000_000_00L);
        engine.registerAccount("B", 200_000_00L);
        engine.registerAccount("C", 50_000_00L);

        engine.depositStock("B", "AAPL", 10_000);
        engine.depositStock("B", "MSFT", 5_000);
        engine.depositStock("B", "TSLA", 2_000);
        engine.depositStock("A", "AAPL", 1_000);
        engine.depositStock("C", "AAPL", 100);

        log.info("演示账户初始化完成: A/B/C（现金单位：分，持仓单位：股）");
    }
}
