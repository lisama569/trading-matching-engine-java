package com.github.lisama569.tradingmatchingenginejava.engine;

import com.github.lisama569.tradingmatchingenginejava.common.TradingException;
import com.github.lisama569.tradingmatchingenginejava.model.Account;
import com.github.lisama569.tradingmatchingenginejava.model.InstrumentSpec;
import com.github.lisama569.tradingmatchingenginejava.model.Order;
import com.github.lisama569.tradingmatchingenginejava.model.OrderType;
import com.github.lisama569.tradingmatchingenginejava.model.RejectCode;
import com.github.lisama569.tradingmatchingenginejava.model.Side;
import com.github.lisama569.tradingmatchingenginejava.model.Trade;
import com.github.lisama569.tradingmatchingenginejava.service.AccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存撮合引擎。
 *
 * <h3>并发模型（不会死锁）</h3>
 * <ol>
 *     <li><b>账户锁</b>：下单前的风控与冻结只持有账户锁，且在申请标的锁之前完成并释放，
 *     因此不存在 "账户锁 -&gt; 标的锁" 的等待边；</li>
 *     <li><b>标的锁</b>：每个 symbol 一把锁，撮合、挂单、撤单、成交交割都在该锁内完成；
 *     成交需要同时改买卖两个账户时，按账户 id 升序获取账户锁；</li>
 *     <li>等待边只有 "标的锁 -&gt; 账户锁" 一种方向，不构成环，所以不可能死锁。
 *     不同标的之间完全并行。</li>
 * </ol>
 *
 * <h3>账务一致性</h3>
 * 冻结在下单时一次完成；每笔成交在同一临界区内同时撮合 + 双方交割，
 * 撤单 / IOC 剩余只释放一次（订单状态终结是唯一开关），因此不会出现
 * "成交了没记账""记了两次""冻结扣成负数" 的情况。
 */
@Component
public class MatchingEngine {

    private static final Logger log = LoggerFactory.getLogger(MatchingEngine.class);
    private static final int MAX_TRADE_HISTORY = 1000;

    private final AccountService accountService;

    private final Map<String, OrderBook> books = new ConcurrentHashMap<>();
    private final Map<String, Object> symbolLocks = new ConcurrentHashMap<>();
    private final Map<String, InstrumentSpec> instruments = new ConcurrentHashMap<>();
    private final Map<Long, Order> ordersById = new ConcurrentHashMap<>();
    private final Map<String, ArrayDeque<Trade>> tradeHistory = new ConcurrentHashMap<>();

    private final AtomicLong idGenerator = new AtomicLong(1);
    private final AtomicLong tradeIdGenerator = new AtomicLong(1);

    public MatchingEngine(AccountService accountService) {
        this.accountService = accountService;
    }

    public void registerInstrument(InstrumentSpec spec) {
        instruments.put(spec.symbol(), spec);
        books.put(spec.symbol(), new OrderBook());
        symbolLocks.put(spec.symbol(), new Object());
        tradeHistory.put(spec.symbol(), new ArrayDeque<>());
    }

    public InstrumentSpec getInstrument(String symbol) {
        InstrumentSpec spec = instruments.get(symbol);
        if (spec == null) {
            throw new TradingException(RejectCode.INSTRUMENT_NOT_FOUND, "标的不存在: " + symbol);
        }
        return spec;
    }

    public long nextOrderId() {
        return idGenerator.getAndIncrement();
    }

    public Order getOrder(long orderId) {
        return ordersById.get(orderId);
    }

    /**
     * 撮合入口（资金/持仓已由 OrderService 完成冻结）。
     * 注册订单后在标的锁内撮合；撤单可能在锁前抢先把订单终结，锁内需复查状态。
     */
    public List<Trade> placeOrder(Order order) {
        ordersById.put(order.getId(), order);
        Object lock = symbolLocks.get(order.getSymbol());
        synchronized (lock) {
            if (!order.isActive()) {
                // 极端竞态：订单刚注册就被并发撤单终结，撤单方已释放全部冻结。
                log.info("订单在撮合前已被终结，直接返回: {}", order);
                return List.of();
            }
            List<Trade> trades = new ArrayList<>();
            match(order, trades);

            if (order.remainingQty() > 0) {
                if (order.getType() == OrderType.LIMIT) {
                    books.get(order.getSymbol()).addResting(order);
                    log.info("限价单剩余 {} 股进入簿中挂单: {}", order.remainingQty(), order);
                } else {
                    // 市价单 = IOC：剩余立即撤销并释放冻结。
                    order.markCanceled(RejectCode.NO_LIQUIDITY);
                    releaseRemainingFreeze(order);
                    log.info("市价单(IOC)无足够对手盘，剩余 {} 股终结: {}", order.remainingQty(), order);
                }
            }
            return trades;
        }
    }

    private void match(Order taker, List<Trade> tradesOut) {
        OrderBook book = books.get(taker.getSymbol());
        InstrumentSpec spec = instruments.get(taker.getSymbol());
        while (taker.remainingQty() > 0) {
            Order maker = book.pollBestActive(taker.getSide());
            if (maker == null) {
                break;
            }
            long makerPrice = maker.getLimitPrice();
            if (taker.getType() == OrderType.LIMIT) {
                long limit = taker.getLimitPrice();
                if (taker.getSide() == Side.BUY ? makerPrice > limit : makerPrice < limit) {
                    break; // 越过限价，停止撮合
                }
            } else if (taker.getSide() == Side.BUY && makerPrice > spec.marketMaxPrice()) {
                break; // 市价买入保护价，防止买到异常高价
            }
            // 市价卖出的对手盘是簿中限价买单，其价格已在 [lowerLimit, upperLimit] 内，天然受保护。

            long qty = Math.min(taker.remainingQty(), maker.remainingQty());
            settleFill(taker, maker, makerPrice, qty);
            taker.recordFill(qty, makerPrice);
            maker.recordFill(qty, makerPrice);
            book.removeIfHeadFilled(maker.getSide());

            Trade trade = new Trade(tradeIdGenerator.getAndIncrement(), taker.getId(), maker.getId(),
                    taker.getAccountId(), maker.getAccountId(), taker.getSymbol(),
                    taker.getSide(), makerPrice, qty, System.currentTimeMillis());
            tradesOut.add(trade);
            ArrayDeque<Trade> history = tradeHistory.get(taker.getSymbol());
            history.addLast(trade);
            while (history.size() > MAX_TRADE_HISTORY) {
                history.removeFirst();
            }
            log.info("成交: taker={}, maker={}, price={}, qty={}", taker.getId(), maker.getId(), makerPrice, qty);
        }
    }

    /**
     * 在标的锁内对买卖双方账户做交割。按账户 id 升序获取账户锁，杜绝同账户多笔成交之间的锁顺序歧义。
     */
    private void settleFill(Order taker, Order maker, long price, long qty) {
        long notional = price * qty;
        Order buyOrder = taker.getSide() == Side.BUY ? taker : maker;
        Order sellOrder = taker.getSide() == Side.SELL ? taker : maker;
        Account buyer = accountService.get(buyOrder.getAccountId());
        Account seller = accountService.get(sellOrder.getAccountId());

        Account first = buyer.getId() <= seller.getId() ? buyer : seller;
        Account second = buyer == seller ? null : (first == buyer ? seller : buyer);
        synchronized (first.lock()) {
            if (second != null) {
                synchronized (second.lock()) {
                    doSettle(buyOrder, sellOrder, buyer, seller, price, qty, notional);
                }
            } else {
                doSettle(buyOrder, sellOrder, buyer, seller, price, qty, notional);
            }
        }
    }

    private void doSettle(Order buyOrder, Order sellOrder, Account buyer, Account seller,
                          long price, long qty, long notional) {
        // 买方：冻结资金转为支付，持仓增加。
        buyer.settleBuyCash(notional);
        buyer.positionOf(buyOrder.getSymbol()).settleBuy(qty);
        buyOrder.consumeFrozenCash(notional);
        // 卖方：冻结持仓扣减，现金增加。
        seller.positionOf(sellOrder.getSymbol()).settleSell(qty);
        seller.settleSellCash(notional);
        sellOrder.consumeFrozenQty(qty);
    }

    /**
     * 撤单。可重入语义全部在标的锁内判定：
     * 找不到 / 不属于该账户 -&gt; ORDER_NOT_FOUND；已终结 -&gt; ORDER_NOT_ACTIVE。
     */
    public Order cancel(long orderId, long requesterAccountId) {
        Order order = ordersById.get(orderId);
        if (order == null || order.getAccountId() != requesterAccountId) {
            throw new TradingException(RejectCode.ORDER_NOT_FOUND,
                    "订单不存在: orderId=" + orderId + ", accountId=" + requesterAccountId);
        }
        Object lock = symbolLocks.get(order.getSymbol());
        synchronized (lock) {
            if (!order.isActive()) {
                throw new TradingException(RejectCode.ORDER_NOT_ACTIVE,
                        "订单已终结，不能撤销: orderId=" + orderId + ", status=" + order.getStatus());
            }
            order.markCanceled(null);
            if (order.getType() == OrderType.LIMIT) {
                books.get(order.getSymbol()).removeOrder(order);
            }
            releaseRemainingFreeze(order);
            log.info("撤单成功，剩余冻结已释放: {}", order);
            return order;
        }
    }

    /** 释放订单上尚未交割的冻结（买方资金 / 卖方持仓）。调用方必须持有标的锁。 */
    private void releaseRemainingFreeze(Order order) {
        Account account = accountService.get(order.getAccountId());
        synchronized (account.lock()) {
            if (order.getSide() == Side.BUY && order.getFrozenCash() > 0) {
                account.releaseFrozenCash(order.getFrozenCash());
                order.consumeFrozenCash(order.getFrozenCash());
            }
            if (order.getSide() == Side.SELL && order.getFrozenQty() > 0) {
                account.positionOf(order.getSymbol()).releaseFrozen(order.getFrozenQty());
                order.consumeFrozenQty(order.getFrozenQty());
            }
        }
    }

    public List<long[]> getBookLevels(String symbol, Side side, int maxLevels) {
        getInstrument(symbol);
        synchronized (symbolLocks.get(symbol)) {
            return books.get(symbol).snapshot(side, maxLevels);
        }
    }

    public List<Trade> getRecentTrades(String symbol, int limit) {
        getInstrument(symbol);
        synchronized (symbolLocks.get(symbol)) {
            List<Trade> all = new ArrayList<>(tradeHistory.get(symbol));
            int from = Math.max(0, all.size() - limit);
            return all.subList(from, all.size());
        }
    }
}
