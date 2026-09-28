package com.github.lisama569.tradingmatchingenginejava.service;

import com.github.lisama569.tradingmatchingenginejava.domain.Order;
import com.github.lisama569.tradingmatchingenginejava.dto.CancelOrderRequest;
import com.github.lisama569.tradingmatchingenginejava.dto.CancelResponse;
import com.github.lisama569.tradingmatchingenginejava.dto.OrderResponse;
import com.github.lisama569.tradingmatchingenginejava.dto.PlaceOrderRequest;
import com.github.lisama569.tradingmatchingenginejava.engine.MatchingEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 非法输入与边界拒绝测试：每种非法情况都有可区分的 rejectReason，
 * 且 service 不抛异常、内部错误不透传。
 */
class RiskRejectionTest {

    private static final Logger log = LoggerFactory.getLogger(RiskRejectionTest.class);

    private MatchingEngine engine;
    private TradingService service;

    private PlaceOrderRequest req(String account, String symbol, String side, String type,
                                  Long price, Long qty) {
        PlaceOrderRequest r = new PlaceOrderRequest();
        r.setAccountId(account);
        r.setSymbol(symbol);
        r.setSide(side);
        r.setType(type);
        r.setPrice(price);
        r.setQuantity(qty);
        return r;
    }

    private void assertReject(PlaceOrderRequest r, String expectedReason) {
        log.info("[输入] account={}, symbol={}, side={}, type={}, price={}, qty={}",
                r.getAccountId(), r.getSymbol(), r.getSide(), r.getType(), r.getPrice(),
                r.getQuantity());
        OrderResponse resp = service.placeOrder(r);
        assertFalse(resp.isSuccess(), "该输入应被拒绝");
        assertEquals(expectedReason, resp.getRejectReason(),
                "拒绝原因必须为 " + expectedReason + "，实际: " + resp.getMessage());
        log.info("[判定] 拒绝成功 reason={}, message={}", resp.getRejectReason(), resp.getMessage());
    }

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine(Set.of("AAPL"), 100, 500_000L);
        engine.registerAccount("A", 100_000L);
        engine.registerAccount("B", 10_000L);
        engine.depositStock("B", "AAPL", 50);
        service = new TradingService(engine, new RiskValidator());
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
    }

    @Test
    void invalidInputsHaveDistinctReasons() {
        assertReject(req(" ", "AAPL", "BUY", "LIMIT", 100L, 10L), "INVALID_ACCOUNT");
        assertReject(req("GHOST", "AAPL", "BUY", "LIMIT", 100L, 10L), "INVALID_ACCOUNT");
        assertReject(req("A", "  ", "BUY", "LIMIT", 100L, 10L), "INVALID_SYMBOL");
        assertReject(req("A", "NVDA", "BUY", "LIMIT", 100L, 10L), "INVALID_SYMBOL");
        assertReject(req("A", "AAPL", "UP", "LIMIT", 100L, 10L), "INVALID_SIDE");
        assertReject(req("A", "AAPL", "BUY", "STOP", 100L, 10L), "INVALID_ORDER_TYPE");
        assertReject(req("A", "AAPL", "BUY", "LIMIT", null, 10L), "INVALID_PRICE");
        assertReject(req("A", "AAPL", "BUY", "LIMIT", 0L, 10L), "INVALID_PRICE");
        assertReject(req("A", "AAPL", "BUY", "LIMIT", -5L, 10L), "INVALID_PRICE");
        assertReject(req("A", "AAPL", "BUY", "LIMIT", 100L, null), "INVALID_QUANTITY");
        assertReject(req("A", "AAPL", "BUY", "LIMIT", 100L, 0L), "INVALID_QUANTITY");
        assertReject(req("A", "AAPL", "BUY", "LIMIT", 100L, -1L), "INVALID_QUANTITY");
        log.info("[结论] 12 类非法输入拒绝原因互不混淆");
    }

    @Test
    void insufficientCashAndPositionAreDistinct() {
        // A 有 100000 分；买 100 股 @1000 = 100000 恰好用完，再买即资金不足。
        // 先构造一笔资金不足：100 股 @2000 = 200000 > 100000（未超名义限额500000）。
        assertReject(req("A", "AAPL", "BUY", "LIMIT", 2_000L, 100L), "INSUFFICIENT_CASH");
        // B 只有 50 股，卖 100 股持仓不足。
        assertReject(req("B", "AAPL", "SELL", "LIMIT", 100L, 100L), "INSUFFICIENT_POSITION");
        // 资金冻结后再次下单应被可用资金拦住（验证冻结真实生效）。
        // 买 100 股 @900 = 90000 冻结；剩可用 10000，再买 20 股 @900 = 18000 不足。
        OrderResponse first = service.placeOrder(req("A", "AAPL", "BUY", "LIMIT", 900L, 100L));
        assertTrue(first.isSuccess(), "首单应成功: " + first.getMessage());
        assertReject(req("A", "AAPL", "BUY", "LIMIT", 900L, 20L), "INSUFFICIENT_CASH");
        log.info("[结论] 资金不足/持仓不足/冻结后不足均正确区分");
    }

    @Test
    void limitsAreEnforced() {
        // 单笔数量上限 100。
        assertReject(req("A", "AAPL", "BUY", "LIMIT", 100L, 101L), "EXCEEDS_LIMIT");
        // 名义金额上限 500000 分：10000*100 = 1,000,000。
        assertReject(req("A", "AAPL", "BUY", "LIMIT", 10_000L, 100L), "EXCEEDS_LIMIT");
        log.info("[结论] 数量与名义金额限额拒绝原因均为 EXCEEDS_LIMIT");
    }

    @Test
    void cancelRejectionsAreDistinct() {
        CancelOrderRequest notFound = new CancelOrderRequest();
        notFound.setAccountId("A");
        notFound.setOrderId(999_999L);
        CancelResponse r1 = service.cancel(notFound);
        assertFalse(r1.isSuccess());
        assertEquals("ORDER_NOT_FOUND", r1.getRejectReason());

        // 先放一笔卖单制造成交：买方全成后撤单 -> ORDER_ALREADY_FINISHED。
        OrderResponse ask = service.placeOrder(req("B", "AAPL", "SELL", "LIMIT", 100L, 10L));
        OrderResponse bid = service.placeOrder(req("A", "AAPL", "BUY", "LIMIT", 100L, 10L));
        assertTrue(bid.isSuccess());

        CancelOrderRequest finished = new CancelOrderRequest();
        finished.setAccountId("A");
        finished.setOrderId(bid.getOrder().getId());
        CancelResponse r2 = service.cancel(finished);
        assertFalse(r2.isSuccess());
        assertEquals("ORDER_ALREADY_FINISHED", r2.getRejectReason());

        // 不能撤他人订单（对外表现为查无此单，不泄露存在性）。
        CancelOrderRequest other = new CancelOrderRequest();
        other.setAccountId("B");
        other.setOrderId(bid.getOrder().getId());
        CancelResponse r3 = service.cancel(other);
        assertEquals("ORDER_NOT_FOUND", r3.getRejectReason());
        log.info("[判定] 撤单不存在/已结束/非本人 -> {} / {} / {}",
                r1.getRejectReason(), r2.getRejectReason(), r3.getRejectReason());
    }

    @Test
    void internalExceptionNeverLeaksToCaller() {
        // null 请求由 service 兜成结构化失败响应，不抛异常。
        OrderResponse resp = service.placeOrder(null);
        assertFalse(resp.isSuccess());
        assertTrue(resp.getRejectReason() != null && !resp.getRejectReason().isBlank());
        log.info("[判定] 空请求返回结构化失败 reason={}，无堆栈外泄", resp.getRejectReason());
    }
}
