package com.github.lisama569.tradingmatchingenginejava.config;

import com.github.lisama569.tradingmatchingenginejava.engine.MatchingEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.LinkedHashSet;

/**
 * 撮合引擎装配：内存状态全部由 MatchingEngine 自身持有（单例 Bean 即全局账本）。
 */
@Configuration
public class MatchingEngineConfig {

    @Value("${trading.symbols:AAPL,MSFT,TSLA}")
    private String symbols;

    /** 单笔数量上限（股）。 */
    @Value("${trading.limits.max-order-quantity:1000000}")
    private long maxOrderQuantity;

    /** 单笔名义金额上限（分），默认 1 亿元 = 10^10 分。 */
    @Value("${trading.limits.max-order-notional:10000000000}")
    private long maxOrderNotional;

    @Bean
    public MatchingEngine matchingEngine() {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        Arrays.stream(symbols.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(set::add);
        return new MatchingEngine(set, maxOrderQuantity, maxOrderNotional);
    }
}
