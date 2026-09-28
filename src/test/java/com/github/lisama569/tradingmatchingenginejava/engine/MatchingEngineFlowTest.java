package com.github.lisama569.tradingmatchingenginejava.engine;

import com.github.lisama569.tradingmatchingenginejava.domain.Order;
import com.github.lisama569.tradingmatchingenginejava.domain.OrderStatus;
import com.github.lisama569.tradingmatchingenginejava.domain.RejectReason;
import com.github.lisama569.tradingmatchingenginejava.dto.CancelOrderRequest;
import com.github.lisama569.tradingmatchingenginejava.dto.PlaceOrderRequest;
import com.github.lisama569.tradingmatchingenginejava.exception.TradingException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 引擎端到端流程测试：限价撮合、部分成交、撤单、市价单边界，并对资金/持仓做自洽对账。
 */
class MatchingEngineFlowTest {

    private static final Logger log = LoggerFactory.getLogger(MatchingEngineFlowTest.class);

    private MatchingEngine engine;

    private PlaceOrderRequest req(String account, String symbol, String side, String type,
                                  Long price, long qty) {
        PlaceOrderRequest r = new PlaceOrderRequest();
        r.setAccountId(account);
        r.setSymbol(symbol);
        r.setSide(side);
        r.setType(type);
        r.setPrice(price);
        r.setQuantity(qty);
        return r;
    }

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine(Set.of("AAPL"), 1_000_000, 10_000_000_000L);
        engine.registerAccount("buyer", 1_000_000L);
        engine.registerAccount("seller", 100_000L);
        engine.registerAccount("poor", 2_500L);
        engine.depositStock("seller", "AAPL", 1_000);
        engine.depositStock("buyer", "AAPL", 100);
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
    }

    @Test
    void limitCross_fullFill_accountsSettleExactlyOnce() {
        // 卖方挂 100 股 @1000；买方直接吃单。
        Order ask = engine.placeOrder(req("seller", "AAPL", "SELL", "LIMIT", 1000L, 100));
        assertEquals(OrderStatus.NEW, ask.getStatus());
        // 挂单阶段：卖方冻结 100 股，买方冻结资金前校验卖盘。
        assertEquals(100, engine.positionSnapshot("seller", "AAPL")[1]);

        Order bid = engine.placeOrder(req("buyer", "AAPL", "BUY", "LIMIT", 1000L, 100));
        log.info("[输入] 买方限价 1000x100 与卖方限价 1000x100 对敲");

        assertEquals(OrderStatus.FILLED, bid.getStatus());
        assertEquals(OrderStatus.FILLED, ask.getStatus());
        assertEquals(100, bid.getFilledQty());
        assertEquals(1000, bid.getAvgFillPrice());

        // 资金对账：买方现金减少 100*1000=100000；卖方增加 100000；双方冻结均归零。
        assertEquals(900_000L, engine.getAccount("buyer").getCash());
        assertEquals(0L, engine.getAccount("buyer").getFrozenCash());
        assertEquals(200_000L, engine.getAccount("seller").getCash());
        assertEquals(0L, engine.getAccount("seller").getFrozenCash());
        // 持仓对账：买方 100+100=200，卖方 1000-100=900；冻结归零。
        long[] buyerPos = engine.positionSnapshot("buyer", "AAPL");
        long[] sellerPos = engine.positionSnapshot("seller", "AAPL");
        assertEquals(200, buyerPos[0]);
        assertEquals(0, buyerPos[1]);
        assertEquals(900, sellerPos[0]);
        assertEquals(0, sellerPos[1]);
        // 只有一笔成交，不能记两次。
        assertEquals(1, engine.trades("AAPL").size());
        log.info("[结论] 全成对账通过：买方持仓={} 现金={}，卖方持仓={} 现金={}，成交笔数=1",
                buyerPos[0], 900_000L, sellerPos[0], 200_000L);
    }

    @Test
    void partialFill_restsAndKeepsFrozenConsistent() {
        Order ask = engine.placeOrder(req("seller", "AAPL", "SELL", "LIMIT", 1000L, 100));
        Order bid = engine.placeOrder(req("buyer", "AAPL", "BUY", "LIMIT", 1000L, 40));
        log.info("[输入] 卖 1000x100 挂出，买 1000x40 部分成交");

        assertEquals(40, ask.getFilledQty());
        assertEquals(60, ask.getRemainingQty());
        assertEquals(OrderStatus.PARTIALLY_FILLED, ask.getStatus());
        // 卖方剩余 60 股仍冻结。
        assertEquals(60, engine.positionSnapshot("seller", "AAPL")[1]);
        assertEquals(960, engine.positionSnapshot("seller", "AAPL")[0],
                "卖方初始1000股，成交40后应剩960，冻结60");
        // 买方钱货两清，无冻结残留。
        assertEquals(1_000_000L - 40_000L, engine.getAccount("buyer").getCash());
        assertEquals(0, engine.getAccount("buyer").getFrozenCash());
        log.info("[结论] 部分成交：卖方剩余冻结={}，买方现金={}", 60,
                1_000_000L - 40_000L);

        // 撤掉卖方剩余：冻结应全部释放回可用，状态 CANCELED。
        CancelOrderRequest cancel = new CancelOrderRequest();
        cancel.setAccountId("seller");
        cancel.setOrderId(ask.getId());
        Order canceled = engine.cancel(cancel);
        assertEquals(OrderStatus.CANCELED, canceled.getStatus());
        assertEquals(0, engine.positionSnapshot("seller", "AAPL")[1]);
        assertEquals(960, engine.positionSnapshot("seller", "AAPL")[2]);
        log.info("[结论] 撤单解冻：卖方可用持仓回到 {}", 960);

        // 撤销已结束订单必须拒绝且原因可区分。
        TradingException e = assertThrows(TradingException.class, () -> engine.cancel(cancel));
        assertEquals(RejectReason.ORDER_ALREADY_FINISHED, e.getReason());
        log.info("[判定] 重复撤单拒绝原因={}", e.getReason());
    }

    @Test
    void cancelUnfilledBuyOrderReleasesFrozenCash() {
        Order bid = engine.placeOrder(req("buyer", "AAPL", "BUY", "LIMIT", 900L, 10));
        assertEquals(OrderStatus.NEW, bid.getStatus());
        assertEquals(9_000L, engine.getAccount("buyer").getFrozenCash());

        CancelOrderRequest cancel = new CancelOrderRequest();
        cancel.setAccountId("buyer");
        cancel.setOrderId(bid.getId());
        engine.cancel(cancel);
        assertEquals(0L, engine.getAccount("buyer").getFrozenCash());
        assertEquals(1_000_000L, engine.getAccount("buyer").getCash());
        log.info("[结论] 未成交买单撤单后冻结资金全部释放");
    }

    @Test
    void marketOrderBoundaries() {
        // 无对手盘：市价单被明确拒绝，原因 MARKET_NO_LIQUIDITY。
        TradingException noLiq = assertThrows(TradingException.class,
                () -> engine.placeOrder(req("buyer", "AAPL", "BUY", "MARKET", null, 10)));
        assertEquals(RejectReason.MARKET_NO_LIQUIDITY, noLiq.getReason());
        log.info("[判定] 无对手盘市价单拒绝原因={}", noLiq.getReason());

        // 挂卖盘 100@1000；新开一个只有 500 分的账户，连 1 股都买不起，直接拒绝。
        engine.registerAccount("broke", 500L);
        engine.placeOrder(req("seller", "AAPL", "SELL", "LIMIT", 1000L, 100));
        TradingException poorBuy = assertThrows(TradingException.class,
                () -> engine.placeOrder(req("broke", "AAPL", "BUY", "MARKET", null, 10)));
        assertEquals(RejectReason.INSUFFICIENT_CASH, poorBuy.getReason());
        log.info("[判定] 市价买单资金不足拒绝原因={}", poorBuy.getReason());

        // 市价卖单数量超过持仓：直接 INSUFFICIENT_POSITION。
        TradingException overSell = assertThrows(TradingException.class,
                () -> engine.placeOrder(req("poor", "AAPL", "SELL", "MARKET", null, 10)));
        assertEquals(RejectReason.INSUFFICIENT_POSITION, overSell.getReason());

        // 市价买单量大于卖盘：成交全部卖盘，余量作废，钱货对得上。
        Order mkt = engine.placeOrder(req("buyer", "AAPL", "BUY", "MARKET", null, 200));
        assertEquals(100, mkt.getFilledQty());
        assertEquals(100, mkt.getRemainingQty());
        assertEquals(OrderStatus.CANCELED, mkt.getStatus());
        assertEquals(900_000L, engine.getAccount("buyer").getCash());
        log.info("[结论] 市价买单吃穿卖盘后余量作废，实际成交={} 状态={}",
                mkt.getFilledQty(), mkt.getStatus());
    }

    @Test
    void marketBuyIsTruncatedByAvailableCash() {
        // 卖盘 100@1000，poor 有 2500 分，只能整股买 2 股。
        engine.placeOrder(req("seller", "AAPL", "SELL", "LIMIT", 1000L, 100));
        Order mkt = engine.placeOrder(req("poor", "AAPL", "BUY", "MARKET", null, 10));
        assertEquals(2, mkt.getFilledQty());
        assertEquals(8, mkt.getRemainingQty());
        assertEquals(OrderStatus.CANCELED, mkt.getStatus());
        assertEquals(500L, engine.getAccount("poor").getCash(), "2股*1000=2000，余500");
        assertEquals(2, engine.positionSnapshot("poor", "AAPL")[0]);
        log.info("[结论] 市价买单按可用资金截短成交 {} 股，余500分，余量作废", 2);
    }
}
