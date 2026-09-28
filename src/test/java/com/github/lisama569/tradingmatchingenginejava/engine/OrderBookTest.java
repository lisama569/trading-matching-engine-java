package com.github.lisama569.tradingmatchingenginejava.engine;

import com.github.lisama569.tradingmatchingenginejava.domain.Order;
import com.github.lisama569.tradingmatchingenginejava.domain.OrderStatus;
import com.github.lisama569.tradingmatchingenginejava.domain.OrderType;
import com.github.lisama569.tradingmatchingenginejava.domain.Side;
import com.github.lisama569.tradingmatchingenginejava.domain.Trade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单簿撮合顺序单测：验证价格优先、同价时间优先、部分成交与惰性撤单。
 * 不经过账户结算，FillSink 用测试桩记录判定依据。
 */
class OrderBookTest {

    private static final Logger log = LoggerFactory.getLogger(OrderBookTest.class);

    private OrderBook book;
    private long idSeq = 1;
    private long seq = 1;
    private final List<String> fillLog = new ArrayList<>();

    private FillSink recordingSink() {
        return new FillSink() {
            @Override
            public long allowedQty(Order taker, Order maker, long price, long requestedQty) {
                log.info("[判定] taker={} 与 maker={} 撮合: price={}, requestedQty={} -> 全部允许",
                        taker.getId(), maker.getId(), price, requestedQty);
                return requestedQty;
            }

            @Override
            public Trade onFill(Order taker, Order maker, long price, long qty) {
                fillLog.add(maker.getId() + "@" + price + "x" + qty);
                log.info("[成交输入] takerId={}, makerId={}, price={}, qty={}",
                        taker.getId(), maker.getId(), price, qty);
                return null;
            }
        };
    }

    private Order limit(String account, Side side, long price, long qty) {
        return new Order(idSeq++, account, "AAPL", side, OrderType.LIMIT, price, qty,
                seq++, 0L);
    }

    private Order market(String account, Side side, long qty) {
        return new Order(idSeq++, account, "AAPL", side, OrderType.MARKET, 0L, qty,
                seq++, 0L);
    }

    @BeforeEach
    void setUp() {
        book = new OrderBook();
        fillLog.clear();
    }

    @Test
    void pricePriority_buyTakerHitsLowestAskFirst() {
        Order ask2 = limit("B", Side.SELL, 105, 10);
        Order ask1 = limit("C", Side.SELL, 100, 10);
        Order ask3 = limit("D", Side.SELL, 103, 10);
        book.add(ask2);
        book.add(ask1);
        book.add(ask3);

        Order buy = limit("A", Side.BUY, 110, 25);
        log.info("[输入] 限价买单 price=110 qty=25，卖盘: 100x10,103x10,105x10");
        book.match(buy, recordingSink());

        // 期望按 100 -> 103 -> 105 的价格顺序各成交 10、10、5，成交价取 maker 价。
        assertEquals(List.of(ask1.getId() + "@100x10", ask3.getId() + "@103x10", ask2.getId() + "@105x5"),
                fillLog, "必须严格价格优先，成交价为 maker 价");
        assertEquals(25, buy.getFilledQty());
        assertEquals(OrderStatus.FILLED, buy.getStatus());
        assertEquals(100 * 10L + 103 * 10L + 105 * 5L, buy.getFilledNotional());
        assertEquals(5, ask2.getRemainingQty());
        assertEquals(OrderStatus.PARTIALLY_FILLED, ask2.getStatus());
        assertEquals(1, book.askLevels());
        log.info("[结论] 价格优先验证通过，买单成交均价={}", buy.getAvgFillPrice());
    }

    @Test
    void timePriority_samePriceIsFifo() {
        Order a = limit("B", Side.SELL, 100, 5);
        Order b = limit("C", Side.SELL, 100, 5);
        Order c = limit("D", Side.SELL, 100, 5);
        book.add(a);
        book.add(b);
        book.add(c);

        Order buy = limit("A", Side.BUY, 100, 12);
        log.info("[输入] 同价卖盘三笔各5，限价买单 qty=12；入队顺序 maker={},{},{}",
                a.getId(), b.getId(), c.getId());
        book.match(buy, recordingSink());

        // FIFO：第一笔全成、第二笔全成、第三笔成交 2。
        assertEquals(List.of(a.getId() + "@100x5", b.getId() + "@100x5", c.getId() + "@100x2"),
                fillLog, "同价位必须按入队时间 FIFO");
        assertEquals(3, c.getRemainingQty());
        log.info("[结论] 同价时间优先验证通过");
    }

    @Test
    void limitOrderOutsideSpreadRestsAndPartialFill() {
        Order ask = limit("B", Side.SELL, 100, 10);
        book.add(ask);

        Order buy = limit("A", Side.BUY, 99, 10);
        log.info("[输入] 买单 price=99 低于卖一 100，不能成交，应入簿");
        book.match(buy, recordingSink());
        assertTrue(fillLog.isEmpty());
        assertEquals(OrderStatus.NEW, buy.getStatus());
        assertTrue(book.add(buy) == false ? false : book.bidLevels() == 1);
        assertEquals(1, book.bidLevels());

        // 来一笔卖单只打穿买盘 4 股。
        Order sell = limit("C", Side.SELL, 98, 4);
        log.info("[输入] 卖单 price=98 qty=4 与买一 99 撮合，应部分成交买方");
        book.match(sell, recordingSink());
        assertEquals(4, buy.getFilledQty());
        assertEquals(6, buy.getRemainingQty());
        assertEquals(OrderStatus.PARTIALLY_FILLED, buy.getStatus());
        assertEquals(1, book.bidLevels(), "部分成交后剩余仍留在簿中");
        log.info("[结论] 不穿价入簿 + 部分成交验证通过，买方剩余={}", buy.getRemainingQty());
    }

    @Test
    void cancelIsLazyButStopsMatching() {
        Order a = limit("B", Side.SELL, 100, 5);
        Order b = limit("C", Side.SELL, 100, 5);
        book.add(a);
        book.add(b);

        Order removed = book.remove(a.getId());
        assertEquals(a, removed);
        assertEquals(OrderStatus.CANCELED, a.getStatus());
        log.info("[输入] 撤销同价队首订单 id={}（惰性删除，先不摘除）", a.getId());

        Order buy = limit("A", Side.BUY, 100, 8);
        book.match(buy, recordingSink());
        // 被撤单绝不参与成交，撮合直接跳到 b；b 全成 5，买方剩 3。
        assertEquals(List.of(b.getId() + "@100x5"), fillLog, "已撤单不能成交");
        assertEquals(5, buy.getFilledQty());
        assertEquals(3, buy.getRemainingQty());
        assertEquals(0, book.askLevels(), "撮合清理后价位应被移除");
        assertNull(book.remove(999_999L), "不存在的订单撤单返回 null");
        log.info("[结论] 惰性撤单不会成交、且在后续撮合中被清理");
    }

    @Test
    void marketBuyConsumesEntireAskLadder() {
        book.add(limit("B", Side.SELL, 100, 3));
        book.add(limit("C", Side.SELL, 101, 3));
        Order mkt = market("A", Side.BUY, 10);
        log.info("[输入] 市价买单 qty=10，卖盘 100x3,101x3，撮合后余量由引擎作废，簿内全空");
        book.match(mkt, recordingSink());
        assertEquals(6, mkt.getFilledQty());
        assertEquals(4, mkt.getRemainingQty());
        assertEquals(0, book.askLevels());
        log.info("[结论] 市价单吃穿对手盘后停止，余量={}", mkt.getRemainingQty());
    }
}
