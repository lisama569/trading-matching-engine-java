package com.github.lisama569.tradingmatchingenginejava.tests;

import com.github.lisama569.tradingmatchingenginejava.common.TradingException;
import com.github.lisama569.tradingmatchingenginejava.engine.MatchingEngine;
import com.github.lisama569.tradingmatchingenginejava.model.Account;
import com.github.lisama569.tradingmatchingenginejava.model.InstrumentSpec;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 风控校验单测：每一种拒绝原因都要能区分到具体 RejectCode，
 * 且被拒绝后账户资金/持仓冻结不能发生任何变化。
 */
class RiskControlTest {

    private static final Logger log = LoggerFactory.getLogger(RiskControlTest.class);

    private AccountService accountService;
    private OrderService orderService;
    private MatchingEngine engine;

    @BeforeEach
    void setUp() {
        accountService = new AccountService();
        engine = new MatchingEngine(accountService);
        orderService = new OrderService(engine, accountService);
        // ETH: lot=10, tick=1, 价格带 1000~5000, 单笔最大 100000 股 / 100_000_000 分
        engine.registerInstrument(new InstrumentSpec(
                "ETH", "Ether Demo", 10, 1, 1000, 5000, 100_000, 100_000_000L, 5000));
        Account a1 = new Account(1, "small-cash", 10_000);
        Account a2 = new Account(2, "holder", 0);
        a2.depositPosition("ETH", 100);
        accountService.register(a1);
        accountService.register(a2);
    }

    private RejectCode expectReject(Runnable action, RejectCode expected, String inputDesc) {
        log.info("[用例输入] {} -> 期望拒绝码 {}", inputDesc, expected);
        TradingException ex = assertThrows(TradingException.class, action::run);
        assertEquals(expected, ex.getCode(), "拒绝原因不匹配: " + inputDesc);
        log.info("[判定依据] 实际拒绝 code={}, message={}", ex.getCode(), ex.getMessage());
        return ex.getCode();
    }

    @Test
    @DisplayName("资源不存在类：账户 / 标的 / 订单")
    void notFoundRejects() {
        expectReject(() -> orderService.submit(99, "ETH", "BUY", "LIMIT", 2000L, 10),
                RejectCode.ACCOUNT_NOT_FOUND, "不存在的账户 99");
        expectReject(() -> orderService.submit(1, "DOGE", "BUY", "LIMIT", 2000L, 10),
                RejectCode.INSTRUMENT_NOT_FOUND, "不存在的标的 DOGE");
        expectReject(() -> orderService.cancel(1, 999_999),
                RejectCode.ORDER_NOT_FOUND, "撤销不存在的订单 999999");
    }

    @Test
    @DisplayName("非法方向与类型")
    void invalidSideAndType() {
        expectReject(() -> orderService.submit(1, "ETH", "HOLD", "LIMIT", 2000L, 10),
                RejectCode.INVALID_SIDE, "side=HOLD");
        expectReject(() -> orderService.submit(1, "ETH", null, "LIMIT", 2000L, 10),
                RejectCode.INVALID_SIDE, "side=null");
        expectReject(() -> orderService.submit(1, "ETH", "BUY", "STOP", 2000L, 10),
                RejectCode.INVALID_ORDER_TYPE, "type=STOP");
    }

    @Test
    @DisplayName("非法价格：为空、非正、不满足最小变动单位、超出价格带")
    void invalidPrice() {
        expectReject(() -> orderService.submit(1, "ETH", "BUY", "LIMIT", null, 10),
                RejectCode.INVALID_PRICE, "限价单 price=null");
        expectReject(() -> orderService.submit(1, "ETH", "BUY", "LIMIT", 0L, 10),
                RejectCode.INVALID_PRICE, "price=0");
        expectReject(() -> orderService.submit(1, "ETH", "BUY", "LIMIT", -2000L, 10),
                RejectCode.INVALID_PRICE, "price=-2000");
        // BTC tick=5，10303 不合法
        engine.registerInstrument(new InstrumentSpec(
                "BTC", "Bitcoin Demo", 1, 5, 10000, 20000, 10_000, 1_000_000_000L, 20000));
        expectReject(() -> orderService.submit(1, "BTC", "BUY", "LIMIT", 10303L, 1),
                RejectCode.INVALID_PRICE, "BTC price=10303 不是 tick=5 的整数倍");
        expectReject(() -> orderService.submit(1, "ETH", "BUY", "LIMIT", 999L, 10),
                RejectCode.PRICE_OUT_OF_BAND, "ETH price=999 低于跌停 1000");
        expectReject(() -> orderService.submit(1, "ETH", "BUY", "LIMIT", 5001L, 10),
                RejectCode.PRICE_OUT_OF_BAND, "ETH price=5001 高于涨停 5000");
    }

    @Test
    @DisplayName("非法数量与超限额：零、负数、非整手、超过单笔最大量")
    void invalidQtyAndLimit() {
        expectReject(() -> orderService.submit(1, "ETH", "BUY", "LIMIT", 2000L, 0),
                RejectCode.INVALID_QTY, "qty=0");
        expectReject(() -> orderService.submit(1, "ETH", "BUY", "LIMIT", 2000L, -5),
                RejectCode.INVALID_QTY, "qty=-5");
        expectReject(() -> orderService.submit(1, "ETH", "BUY", "LIMIT", 2000L, 15),
                RejectCode.INVALID_QTY, "ETH qty=15 不是 lot=10 的整数倍");
        expectReject(() -> orderService.submit(1, "ETH", "BUY", "LIMIT", 2000L, 200_000),
                RejectCode.QTY_EXCEEDS_LIMIT, "qty=200000 超过最大 100000");
    }

    @Test
    @DisplayName("资金不足：买单冻结额超过可用资金，且不能出现负冻结")
    void insufficientCash() {
        // 账户1 只有 10000 分；2000*10=20000
        expectReject(() -> orderService.submit(1, "ETH", "BUY", "LIMIT", 2000L, 10),
                RejectCode.INSUFFICIENT_CASH, "需要 20000 但只有 10000");

        // 连续多次下单把可用资金打满后，下一笔必须仍被拒绝而不是扣穿
        orderService.submit(1, "ETH", "BUY", "LIMIT", 1000L, 10); // 冻结 10000
        assertEquals(0, accountService.get(1).availableCash());
        expectReject(() -> orderService.submit(1, "ETH", "BUY", "LIMIT", 1000L, 10),
                RejectCode.INSUFFICIENT_CASH, "可用资金为 0 时再次下单");
        assertEquals(10_000, accountService.get(1).getFrozenCash(), "拒绝单不能改变冻结额");
    }

    @Test
    @DisplayName("持仓不足：卖单超过可用持仓；冻结持仓不能为负")
    void insufficientPosition() {
        // 账户2 持有 100 ETH
        expectReject(() -> orderService.submit(2, "ETH", "SELL", "LIMIT", 2000L, 200),
                RejectCode.INSUFFICIENT_POSITION, "卖出 200 但只持有 100");

        orderService.submit(2, "ETH", "SELL", "LIMIT", 2000L, 100); // 冻结全部 100
        assertEquals(0, accountService.get(2).positionOf("ETH").available());
        expectReject(() -> orderService.submit(2, "ETH", "SELL", "LIMIT", 2000L, 10),
                RejectCode.INSUFFICIENT_POSITION, "可用持仓为 0 时再次卖出");
        assertEquals(100, accountService.get(2).positionOf("ETH").getFrozenQty());
    }

    @Test
    @DisplayName("撤别人的单按 ORDER_NOT_FOUND 处理；撤已成交单为 ORDER_NOT_ACTIVE")
    void cancelOwnershipAndTerminal() {
        var placed = orderService.submit(2, "ETH", "SELL", "LIMIT", 2000L, 10);
        expectReject(() -> orderService.cancel(1, placed.order().getId()),
                RejectCode.ORDER_NOT_FOUND, "账户1撤销账户2的订单（无权访问视为不存在）");

        // 账户1 现金不足吃不掉；挂一笔能全吃的买方（用账户2 自己买不行——现金 0，改用新增账户）
        Account rich = new Account(3, "rich", 1_000_000);
        accountService.register(rich);
        orderService.submit(3, "ETH", "BUY", "LIMIT", 2000L, 10);
        assertEquals(OrderStatus.FILLED, placed.order().getStatus());
        expectReject(() -> orderService.cancel(2, placed.order().getId()),
                RejectCode.ORDER_NOT_ACTIVE, "撤销已 FILLED 的订单");
    }

    @Test
    @DisplayName("被拒绝的订单不留任何痕迹：无订单 id 注册、无冻结、无盘口")
    void rejectionHasNoSideEffect() {
        log.info("[用例输入] 一系列必然被风控拒绝的下单");
        assertThrows(TradingException.class,
                () -> orderService.submit(1, "ETH", "BUY", "LIMIT", 2000L, 10));
        assertThrows(TradingException.class,
                () -> orderService.submit(2, "ETH", "SELL", "LIMIT", 2000L, 100_000_000L));
        assertEquals(10_000, accountService.get(1).getCash());
        assertEquals(0, accountService.get(1).getFrozenCash());
        assertEquals(100, accountService.get(2).positionOf("ETH").getQty());
        assertEquals(0, accountService.get(2).positionOf("ETH").getFrozenQty());
        assertTrue(engine.getBookLevels("ETH", Side.BUY, 10).isEmpty());
        assertTrue(engine.getBookLevels("ETH", Side.SELL, 10).isEmpty());
        log.info("[判定依据] 拒绝后现金/冻结/持仓/盘口均无变化");
    }
}
