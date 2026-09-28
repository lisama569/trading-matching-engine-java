package com.github.lisama569.tradingmatchingenginejava.tests;

import com.github.lisama569.tradingmatchingenginejava.engine.MatchingEngine;
import com.github.lisama569.tradingmatchingenginejava.model.Account;
import com.github.lisama569.tradingmatchingenginejava.model.InstrumentSpec;
import com.github.lisama569.tradingmatchingenginejava.model.Order;
import com.github.lisama569.tradingmatchingenginejava.model.OrderStatus;
import com.github.lisama569.tradingmatchingenginejava.model.OrderType;
import com.github.lisama569.tradingmatchingenginejava.model.RejectCode;
import com.github.lisama569.tradingmatchingenginejava.model.Side;
import com.github.lisama569.tradingmatchingenginejava.model.Trade;
import com.github.lisama569.tradingmatchingenginejava.service.AccountService;
import com.github.lisama569.tradingmatchingenginejava.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 撮合规则单测（不起 Spring，直接手工接线）。
 * 每个用例都打印订单输入与判定依据，便于复盘撮合结论。
 */
class MatchingEngineTest {

    private static final Logger log = LoggerFactory.getLogger(MatchingEngineTest.class);

    private AccountService accountService;
    private MatchingEngine engine;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        accountService = new AccountService();
        engine = new MatchingEngine(accountService);
        orderService = new OrderService(engine, accountService);
        engine.registerInstrument(new InstrumentSpec(
                "BTC", "Bitcoin Demo", 1, 5, 10000, 20000, 10_000, 1_000_000_000L, 20000));
        Account buyer = new Account(1, "buyer", 1_000_000);
        Account seller = new Account(2, "seller", 0);
        seller.depositPosition("BTC", 1_000);
        accountService.register(buyer);
        accountService.register(seller);
    }

    @Test
    @DisplayName("限价单完全成交：价格、数量、状态、双方资金持仓全部对上")
    void limitOrderFullyFilled() {
        log.info("[用例输入] 卖方先挂 LIMIT SELL BTC price=10500 qty=10；买方再下 LIMIT BUY price=10500 qty=10");
        OrderService.OrderResult r1 = orderService.submit(2, "BTC", "SELL", "LIMIT", 10500L, 10);
        OrderService.OrderResult r2 = orderService.submit(1, "BTC", "BUY", "LIMIT", 10500L, 10);

        Order maker = r1.order();
        Order taker = r2.order();
        log.info("[判定依据] 期望：买卖价相同可成交，成交价取 maker 挂单价 10500，双方均 FILLED");

        assertEquals(OrderStatus.FILLED, maker.getStatus());
        assertEquals(OrderStatus.FILLED, taker.getStatus());
        assertEquals(1, r2.trades().size());
        Trade t = r2.trades().get(0);
        assertEquals(10500, t.price(), "成交价必须是 maker 挂单价");
        assertEquals(10, t.qty());
        assertEquals(taker.getId(), t.takerOrderId());
        assertEquals(maker.getId(), t.makerOrderId());

        Account buyer = accountService.get(1);
        Account seller = accountService.get(2);
        log.info("[账户核对] buyer cash={} 期望 895000, BTC 持仓=10; seller cash={} 期望 105000",
                buyer.getCash(), seller.getCash());
        assertEquals(895_000, buyer.getCash());
        assertEquals(0, buyer.getFrozenCash());
        assertEquals(10, buyer.positionOf("BTC").getQty());
        assertEquals(105_000, seller.getCash());
        assertEquals(0, seller.getFrozenCash());
        assertEquals(990, seller.positionOf("BTC").getQty());
        assertEquals(0, seller.positionOf("BTC").getFrozenQty());
    }

    @Test
    @DisplayName("部分成交：吃单只吃掉部分挂量，剩余挂单保持 PARTIALLY_FILLED 且冻结正确")
    void partialFill() {
        log.info("[用例输入] SELL 10500x20 挂单；随后 BUY 10500x8");
        OrderService.OrderResult sell = orderService.submit(2, "BTC", "SELL", "LIMIT", 10500L, 20);
        OrderService.OrderResult buy = orderService.submit(1, "BTC", "BUY", "LIMIT", 10500L, 8);
        log.info("[判定依据] 期望：卖方剩余 12 挂在簿中(PARTIALLY_FILLED)，冻结持仓=12，买方 FILLED");

        Order sellerOrder = sell.order();
        assertEquals(OrderStatus.PARTIALLY_FILLED, sellerOrder.getStatus());
        assertEquals(8, sellerOrder.getFilledQty());
        assertEquals(12, sellerOrder.remainingQty());
        assertEquals(OrderStatus.FILLED, buy.order().getStatus());

        Account seller = accountService.get(2);
        assertEquals(12, seller.positionOf("BTC").getFrozenQty(), "未成交部分继续冻结");
        assertEquals(992, seller.positionOf("BTC").getQty());
        List<long[]> asks = engine.getBookLevels("BTC", Side.SELL, 10);
        assertEquals(1, asks.size());
        assertEquals(10500, asks.get(0)[0]);
        assertEquals(12, asks.get(0)[2], "盘口活跃量应为剩余 12");
    }

    @Test
    @DisplayName("价格优先 + 同价时间优先：按最优价、再按 FIFO 顺序成交")
    void priceThenTimePriority() {
        log.info("[用例输入] 卖盘：10600x5(先)、10500x3、10500x4(同价较晚)；买方 10600x10");
        Order o1 = orderService.submit(2, "BTC", "SELL", "LIMIT", 10600L, 5).order();
        Order o2 = orderService.submit(2, "BTC", "SELL", "LIMIT", 10500L, 3).order();
        Order o3 = orderService.submit(2, "BTC", "SELL", "LIMIT", 10500L, 4).order();
        OrderService.OrderResult buy = orderService.submit(1, "BTC", "BUY", "LIMIT", 10600L, 10);

        log.info("[判定依据] 期望成交顺序：10500x3(o2) -> 10500x4(o3) -> 10600x3(o1)，成交价逐笔取 maker 价");
        List<Trade> trades = buy.trades();
        assertEquals(3, trades.size());
        assertEquals(o2.getId(), trades.get(0).makerOrderId());
        assertEquals(10500, trades.get(0).price());
        assertEquals(3, trades.get(0).qty());
        assertEquals(o3.getId(), trades.get(1).makerOrderId());
        assertEquals(4, trades.get(1).qty());
        assertEquals(o1.getId(), trades.get(2).makerOrderId());
        assertEquals(3, trades.get(2).qty());
        assertEquals(10600, trades.get(2).price());

        assertEquals(OrderStatus.FILLED, o2.getStatus());
        assertEquals(OrderStatus.FILLED, o3.getStatus());
        assertEquals(OrderStatus.PARTIALLY_FILLED, o1.getStatus());
        assertEquals(2, o1.remainingQty());

        long expectedCost = 10500L * 7 + 10600L * 3;
        assertEquals(expectedCost, 1_000_000 - accountService.get(1).getCash(),
                "买方总支出必须等于逐笔成交额之和");
    }

    @Test
    @DisplayName("限价不穿越：买价低于最低卖价时不成交，订单挂入簿中并全额冻结")
    void limitDoesNotCross() {
        log.info("[用例输入] SELL 11000x10；BUY 10900x10（买价低于卖价）");
        orderService.submit(2, "BTC", "SELL", "LIMIT", 11000L, 10);
        OrderService.OrderResult buy = orderService.submit(1, "BTC", "BUY", "LIMIT", 10900L, 10);
        log.info("[判定依据] 期望：无成交，买单 NEW 挂簿，冻结资金 109000，盘口买一 10900");

        assertEquals(OrderStatus.NEW, buy.order().getStatus());
        assertEquals(0, buy.order().getFilledQty());
        assertEquals(109_000, accountService.get(1).getFrozenCash());
        assertEquals(10900, engine.getBookLevels("BTC", Side.BUY, 1).get(0)[0]);
    }

    @Test
    @DisplayName("市价单 IOC：有对手盘全部成交；无对手盘则 CANCELED/NO_LIQUIDITY 并释放冻结")
    void marketOrderIoc() {
        log.info("[用例输入1] SELL LIMIT 10800x6；买方 MARKET x10（只能成交 6）");
        orderService.submit(2, "BTC", "SELL", "LIMIT", 10800L, 6);
        OrderService.OrderResult mkt = orderService.submit(1, "BTC", "BUY", "MARKET", null, 10);

        log.info("[判定依据] 期望：成交 6 股、剩余 4 股 IOC 撤销(NO_LIQUIDITY)，只按实际成交额扣款，多冻资金释放");
        assertEquals(OrderStatus.CANCELED, mkt.order().getStatus());
        assertEquals(RejectCode.NO_LIQUIDITY, mkt.order().getRejectReason());
        assertEquals(6, mkt.order().getFilledQty());
        assertEquals(4, mkt.order().remainingQty());
        assertEquals(6, mkt.trades().stream().mapToLong(Trade::qty).sum());
        Account buyer = accountService.get(1);
        assertEquals(1_000_000 - 10800L * 6, buyer.getCash(), "只扣实际成交金额");
        assertEquals(0, buyer.getFrozenCash(), "保护价多冻结的资金必须释放");

        log.info("[用例输入2] 清空盘口后买方再下 MARKET x3");
        OrderService.OrderResult noLiquidity = orderService.submit(1, "BTC", "BUY", "MARKET", null, 3);
        assertEquals(OrderStatus.CANCELED, noLiquidity.order().getStatus());
        assertEquals(RejectCode.NO_LIQUIDITY, noLiquidity.order().getRejectReason());
        assertEquals(0, noLiquidity.order().getFilledQty());
        assertEquals(0, accountService.get(1).getFrozenCash());
        assertNull(noLiquidity.order().getLimitPrice());
        assertTrue(noLiquidity.trades().isEmpty());
    }

    @Test
    @DisplayName("撤单：限价挂单撤销后从盘口移除，冻结全部释放，且不能二次撤销")
    void cancelRestingOrder() {
        log.info("[用例输入] BUY LIMIT 10300x5 挂单后立即撤单");
        Order o = orderService.submit(1, "BTC", "BUY", "LIMIT", 10300L, 5).order();
        Order canceled = orderService.cancel(1, o.getId());
        log.info("[判定依据] 期望：CANCELED，买盘清空，冻结资金归 0");

        assertEquals(OrderStatus.CANCELED, canceled.getStatus());
        assertEquals(0, accountService.get(1).getFrozenCash());
        assertTrue(engine.getBookLevels("BTC", Side.BUY, 10).isEmpty());

        // 撤已撤销的单 -> ORDER_NOT_ACTIVE
        try {
            orderService.cancel(1, o.getId());
            throw new AssertionError("二次撤单必须被拒绝");
        } catch (com.github.lisama569.tradingmatchingenginejava.common.TradingException ex) {
            assertEquals(RejectCode.ORDER_NOT_ACTIVE, ex.getCode());
            log.info("[判定依据] 二次撤单被正确拒绝: {}", ex.getCode());
        }
    }
}
