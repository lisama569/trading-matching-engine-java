package com.github.lisama569.tradingmatchingenginejava.engine;

import com.github.lisama569.tradingmatchingenginejava.domain.Account;
import com.github.lisama569.tradingmatchingenginejava.domain.Order;
import com.github.lisama569.tradingmatchingenginejava.domain.OrderType;
import com.github.lisama569.tradingmatchingenginejava.domain.Position;
import com.github.lisama569.tradingmatchingenginejava.domain.RejectReason;
import com.github.lisama569.tradingmatchingenginejava.domain.Side;
import com.github.lisama569.tradingmatchingenginejava.domain.Trade;
import com.github.lisama569.tradingmatchingenginejava.dto.CancelOrderRequest;
import com.github.lisama569.tradingmatchingenginejava.dto.PlaceOrderRequest;
import com.github.lisama569.tradingmatchingenginejava.exception.TradingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 内存撮合引擎。
 *
 * 并发模型（保证一致性的两条主线）：
 *  1) 同一 symbol 的撮合/撤单/入队全部在该 symbol 的单线程执行器内串行完成，
 *     因此订单状态与订单簿的修改不存在竞争，时间优先级 = 入队顺序，确定可复现；
 *  2) 账户资金/持仓的每一次修改都持有该账户的 ReentrantLock。成交涉及两个账户时，
 *     始终按 accountId 升序加锁，避免环路死锁；冻结阶段只持有单个账户锁。
 *
 * 记账与撮合不重复、不遗漏：每笔成交在 onFill 中一次性完成「买卖双方 + 资金/持仓 + Trade」，
 * 订单数量由 OrderBook 单点扣减，市价单则在 allowedQty 中按可用资金逐笔截短，
 * 从机制上杜绝「成交了没记账」「记两次」「超量成交」「冻结扣成负数」。
 */
public class MatchingEngine {

    private static final Logger log = LoggerFactory.getLogger(MatchingEngine.class);

    private final Set<String> symbols = ConcurrentHashMap.newKeySet();

    private final ConcurrentHashMap<String, OrderBook> books = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ExecutorService> executors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> symbolSeq = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Account> accounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Position>> positions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> accountLocks = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<Long, Order> allOrders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Trade>> tradesBySymbol = new ConcurrentHashMap<>();

    private final AtomicLong orderIdSeq = new AtomicLong(1_000_000L);
    private final AtomicLong tradeIdSeq = new AtomicLong(5_000_000L);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** 单笔数量上限（股）。 */
    private final long maxOrderQuantity;
    /** 单笔名义金额上限（分）= 价格 × 数量。 */
    private final long maxOrderNotional;

    public MatchingEngine(Set<String> symbols, long maxOrderQuantity, long maxOrderNotional) {
        this.maxOrderQuantity = maxOrderQuantity;
        this.maxOrderNotional = maxOrderNotional;
        symbols.forEach(this::registerSymbol);
    }

    // ---------------------------------------------------------------------
    // 账户/标的注册（内存账本初始化）
    // ---------------------------------------------------------------------

    public final void registerSymbol(String symbol) {
        symbols.add(symbol);
        books.computeIfAbsent(symbol, s -> new OrderBook());
        symbolSeq.computeIfAbsent(symbol, s -> new AtomicLong(0));
        tradesBySymbol.computeIfAbsent(symbol, s -> new ArrayList<>());
    }

    public final void registerAccount(String accountId, long initialCash) {
        accounts.put(accountId, new Account(accountId, initialCash));
        positions.put(accountId, new ConcurrentHashMap<>());
        accountLocks.put(accountId, new ReentrantLock());
    }

    /** 给账户划入标的持仓（初始化/模拟银货兑付用）。 */
    public final void depositStock(String accountId, String symbol, long qty) {
        requireSymbol(symbol);
        ReentrantLock lock = lockOf(accountId);
        lock.lock();
        try {
            Position p = positions.get(accountId).computeIfAbsent(symbol, s -> new Position());
            p.applyBuyFill(qty);
        } finally {
            lock.unlock();
        }
    }

    public Account getAccount(String accountId) { return accounts.get(accountId); }

    /** 账户快照（加锁复制，避免读到中间态）。 */
    public final Map<String, Long> accountBalanceSnapshot(String accountId) {
        Account account = accounts.get(accountId);
        if (account == null) {
            return null;
        }
        ReentrantLock lock = lockOf(accountId);
        lock.lock();
        try {
            Map<String, Long> m = new TreeMap<>();
            m.put("cash", account.getCash());
            m.put("frozenCash", account.getFrozenCash());
            m.put("availableCash", account.getAvailableCash());
            return m;
        } finally {
            lock.unlock();
        }
    }

    /** 某标的持仓快照：[总持仓, 冻结, 可用]。 */
    public final long[] positionSnapshot(String accountId, String symbol) {
        ReentrantLock lock = lockOf(accountId);
        lock.lock();
        try {
            Position p = positions.get(accountId).get(symbol);
            if (p == null) {
                return new long[]{0L, 0L, 0L};
            }
            return new long[]{p.getQty(), p.getFrozenQty(), p.getAvailableQty()};
        } finally {
            lock.unlock();
        }
    }

    public List<Trade> trades(String symbol) {
        return List.copyOf(tradesBySymbol.getOrDefault(symbol, List.of()));
    }

    /** 账户所有标的持仓快照：每个值为 [总持仓, 冻结, 可用]，加锁逐个复制。 */
    public final Map<String, long[]> allPositionsSnapshot(String accountId) {
        if (!accounts.containsKey(accountId)) {
            return null;
        }
        ReentrantLock lock = lockOf(accountId);
        lock.lock();
        try {
            Map<String, long[]> out = new TreeMap<>();
            for (Map.Entry<String, Position> e : positions.get(accountId).entrySet()) {
                Position p = e.getValue();
                if (p.getQty() != 0 || p.getFrozenQty() != 0) {
                    out.put(e.getKey(), new long[]{p.getQty(), p.getFrozenQty(), p.getAvailableQty()});
                }
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------------
    // 下单
    // ---------------------------------------------------------------------

    public Order placeOrder(PlaceOrderRequest request) {
        if (closed.get()) {
            throw new TradingException(RejectReason.SYSTEM_ERROR, "引擎已关闭");
        }
        // 阶段一：参数与限额校验（调用方线程执行，拒绝在冻结前发生）。
        ParsedOrder parsed = validate(request);

        log.info("下单请求: account={}, symbol={}, side={}, type={}, price={}, qty={}",
                parsed.accountId, parsed.symbol, parsed.side, parsed.type,
                parsed.type == OrderType.LIMIT ? parsed.price : "MKT", parsed.quantity);

        // 阶段二：冻结（只持单账户锁）。
        long notional = parsed.type == OrderType.LIMIT ? parsed.price * parsed.quantity : -1L;
        switch (parsed.type) {
            case LIMIT -> {
                if (parsed.side == Side.BUY) {
                    withLock(parsed.accountId, () -> {
                        Account a = accounts.get(parsed.accountId);
                        if (a.getAvailableCash() < notional) {
                            throw reject(RejectReason.INSUFFICIENT_CASH,
                                    "可用资金不足: need=" + notional + ", available=" + a.getAvailableCash());
                        }
                        a.freezeCash(notional);
                    });
                } else {
                    withLock(parsed.accountId, () -> {
                        Position p = positions.get(parsed.accountId).computeIfAbsent(parsed.symbol, s -> new Position());
                        if (p.getAvailableQty() < parsed.quantity) {
                            throw reject(RejectReason.INSUFFICIENT_POSITION,
                                    "可用持仓不足: need=" + parsed.quantity + ", available=" + p.getAvailableQty());
                        }
                        p.freeze(parsed.quantity);
                    });
                }
            }
            case MARKET -> {
                if (parsed.side == Side.SELL) {
                    // 市价卖单同样先冻结持仓，撮合后释放未成交余量。
                    withLock(parsed.accountId, () -> {
                        Position p = positions.get(parsed.accountId).computeIfAbsent(parsed.symbol, s -> new Position());
                        if (p.getAvailableQty() < parsed.quantity) {
                            throw reject(RejectReason.INSUFFICIENT_POSITION,
                                    "可用持仓不足: need=" + parsed.quantity + ", available=" + p.getAvailableQty());
                        }
                        p.freeze(parsed.quantity);
                    });
                }
            }
        }

        // 阶段三：进入 symbol 串行队列撮合。冻结已完成，撮合内不会再因资金/持仓产生拒绝。
        try {
            return runOnSymbol(parsed.symbol, () -> doPlace(parsed));
        } catch (TradingException e) {
            // 串行任务内的拒绝（无对手盘等）：释放已冻结的市价卖单持仓。
            if (parsed.type == OrderType.MARKET && parsed.side == Side.SELL) {
                withLockQuietly(parsed.accountId, () ->
                        positions.get(parsed.accountId).get(parsed.symbol).unfreeze(parsed.quantity));
            }
            throw e;
        }
    }

    private Order doPlace(ParsedOrder parsed) {
        long seq = symbolSeq.get(parsed.symbol).incrementAndGet();
        Order order = new Order(orderIdSeq.incrementAndGet(), parsed.accountId, parsed.symbol,
                parsed.side, parsed.type, parsed.price, parsed.quantity, seq, System.currentTimeMillis());
        allOrders.put(order.getId(), order);

        OrderBook book = books.get(parsed.symbol);

        if (order.getType() == OrderType.MARKET) {
            long best = book.bestOppositePrice(order);
            if (best < 0) {
                log.info("拒绝市价单 orderId={}: 无对手盘", order.getId());
                throw reject(RejectReason.MARKET_NO_LIQUIDITY,
                        "市价单在 " + parsed.symbol + " 没有对手盘");
            }
            if (order.getSide() == Side.BUY) {
                Account a = accounts.get(parsed.accountId);
                long available = withLockResult(parsed.accountId, a::getAvailableCash);
                if (available < best) {
                    log.info("拒绝市价买单 orderId={}: 可用资金 {} 连最优卖价 {} 的1股都付不起",
                            order.getId(), available, best);
                    throw reject(RejectReason.INSUFFICIENT_CASH,
                            "市价买单可用资金不足以买入1股: available=" + available + ", bestAsk=" + best);
                }
            }
        }

        FillSink sink = new SettlementSink();
        book.match(order, sink);

        if (order.getRemainingQty() > 0) {
            if (order.getType() == OrderType.LIMIT) {
                book.add(order);
                log.info("限价单 orderId={} 挂簿: filled={}, remaining={}, status={}",
                        order.getId(), order.getFilledQty(), order.getRemainingQty(), order.getStatus());
            } else {
                // 市价单未成交余量明确定义为「作废」，释放市价卖单对应的冻结持仓。
                long remaining = order.getRemainingQty();
                order.markCanceled();
                if (order.getSide() == Side.SELL) {
                    withLockQuietly(parsed.accountId, () ->
                            positions.get(parsed.accountId).get(parsed.symbol).unfreeze(remaining));
                }
                log.info("市价单 orderId={} 余量作废: filled={}, canceledRemaining={}",
                        order.getId(), order.getFilledQty(), remaining);
            }
        } else {
            log.info("订单 orderId={} 全部成交: qty={}, avgPrice={}",
                    order.getId(), order.getFilledQty(), order.getAvgFillPrice());
        }
        return order;
    }

    // ---------------------------------------------------------------------
    // 撤单
    // ---------------------------------------------------------------------

    public Order cancel(CancelOrderRequest request) {
        if (closed.get()) {
            throw new TradingException(RejectReason.SYSTEM_ERROR, "引擎已关闭");
        }
        String accountId = request.getAccountId();
        Long orderId = request.getOrderId();
        if (accountId == null || accountId.isBlank()) {
            throw reject(RejectReason.INVALID_ACCOUNT, "accountId 为空");
        }
        if (orderId == null) {
            throw reject(RejectReason.ORDER_NOT_FOUND, "orderId 为空");
        }
        Order order = allOrders.get(orderId);
        if (order == null) {
            throw reject(RejectReason.ORDER_NOT_FOUND, "订单不存在: orderId=" + orderId);
        }
        if (!accountId.equals(order.getAccountId())) {
            // 不泄露他人订单是否存在。
            throw reject(RejectReason.ORDER_NOT_FOUND, "订单不存在: orderId=" + orderId);
        }
        if (order.isFinished()) {
            throw reject(RejectReason.ORDER_ALREADY_FINISHED,
                    "订单已结束不能撤销: orderId=" + orderId + ", status=" + order.getStatus());
        }

        log.info("撤单请求: account={}, orderId={}", accountId, orderId);
        return runOnSymbol(order.getSymbol(), () -> doCancel(order));
    }

    private Order doCancel(Order order) {
        // 串行线程内二次确认状态（撮合可能刚好把它成交完）。
        if (order.isFinished()) {
            throw reject(RejectReason.ORDER_ALREADY_FINISHED,
                    "订单已结束不能撤销: orderId=" + order.getId() + ", status=" + order.getStatus());
        }
        OrderBook book = books.get(order.getSymbol());
        // remove 已遍历买卖两侧并惰性标记；返回 null 说明已被撮合清理掉。
        Order found = book.remove(order.getId());
        if (found == null) {
            throw reject(RejectReason.ORDER_ALREADY_FINISHED,
                    "订单已结束或不在簿中: orderId=" + order.getId());
        }

        long remaining = order.getRemainingQty();
        withLockQuietly(order.getAccountId(), () -> {
            if (order.getSide() == Side.BUY) {
                accounts.get(order.getAccountId())
                        .unfreezeCash(order.getPrice() * remaining);
            } else {
                positions.get(order.getAccountId()).get(order.getSymbol()).unfreeze(remaining);
            }
        });
        log.info("撤单成功 orderId={}: 释放冻结 {}", order.getId(),
                order.getSide() == Side.BUY ? "资金=" + order.getPrice() * remaining : "持仓=" + remaining);
        return order;
    }

    public Order getOrder(long orderId) {
        return allOrders.get(orderId);
    }

    // ---------------------------------------------------------------------
    // 参数与限额校验：拒绝原因确定、顺序固定
    // ---------------------------------------------------------------------

    private ParsedOrder validate(PlaceOrderRequest r) {
        ParsedOrder p = new ParsedOrder();
        if (r == null) {
            throw reject(RejectReason.SYSTEM_ERROR, "请求为空");
        }
        if (r.getAccountId() == null || r.getAccountId().isBlank()) {
            throw reject(RejectReason.INVALID_ACCOUNT, "accountId 为空");
        }
        p.accountId = r.getAccountId();
        if (!accounts.containsKey(p.accountId)) {
            throw reject(RejectReason.INVALID_ACCOUNT, "账户不存在: " + p.accountId);
        }
        if (r.getSymbol() == null || r.getSymbol().isBlank()) {
            throw reject(RejectReason.INVALID_SYMBOL, "symbol 为空");
        }
        p.symbol = r.getSymbol();
        if (!symbols.contains(p.symbol)) {
            throw reject(RejectReason.INVALID_SYMBOL, "未知标的: " + p.symbol);
        }
        p.side = parseSide(r.getSide());
        p.type = parseType(r.getType());

        if (r.getQuantity() == null) {
            throw reject(RejectReason.INVALID_QUANTITY, "quantity 为空");
        }
        p.quantity = r.getQuantity();
        if (p.quantity <= 0) {
            throw reject(RejectReason.INVALID_QUANTITY, "quantity 必须为正数: " + p.quantity);
        }
        if (p.quantity > maxOrderQuantity) {
            throw reject(RejectReason.EXCEEDS_LIMIT,
                    "单笔数量超限: qty=" + p.quantity + ", max=" + maxOrderQuantity);
        }

        if (p.type == OrderType.LIMIT) {
            if (r.getPrice() == null) {
                throw reject(RejectReason.INVALID_PRICE, "限价单 price 为空");
            }
            p.price = r.getPrice();
            if (p.price <= 0) {
                throw reject(RejectReason.INVALID_PRICE, "price 必须为正数: " + p.price);
            }
            long notional = safeMultiply(p.price, p.quantity);
            if (notional > maxOrderNotional) {
                throw reject(RejectReason.EXCEEDS_LIMIT,
                        "单笔名义金额超限: notional=" + notional + ", max=" + maxOrderNotional);
            }
        } else {
            p.price = 0L;
        }
        return p;
    }

    private Side parseSide(String side) {
        if (side == null || side.isBlank()) {
            throw reject(RejectReason.INVALID_SIDE, "side 为空");
        }
        try {
            return Side.valueOf(side.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw reject(RejectReason.INVALID_SIDE, "未知方向: " + side);
        }
    }

    private OrderType parseType(String type) {
        if (type == null || type.isBlank()) {
            throw reject(RejectReason.INVALID_ORDER_TYPE, "type 为空");
        }
        try {
            return OrderType.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw reject(RejectReason.INVALID_ORDER_TYPE, "未知订单类型: " + type);
        }
    }

    private long safeMultiply(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException e) {
            // 溢出按限额拒绝处理，不向外暴露算术异常。
            throw reject(RejectReason.EXCEEDS_LIMIT, "名义金额溢出: " + a + "*" + b);
        }
    }

    // ---------------------------------------------------------------------
    // 成交结算：买卖双方资金/持仓一次完成，账户锁按 id 升序
    // ---------------------------------------------------------------------

    private final class SettlementSink implements FillSink {

        @Override
        public long allowedQty(Order taker, Order maker, long price, long requestedQty) {
            if (taker.getType() == OrderType.MARKET && taker.getSide() == Side.BUY) {
                // 市价买单没有预冻，按当前可用资金能买的整股数截短。
                long available = withLockResult(taker.getAccountId(),
                        () -> accounts.get(taker.getAccountId()).getAvailableCash());
                long affordable = available / price;
                return Math.min(requestedQty, affordable);
            }
            // 限价买卖与市价卖单：下单时已按本单总量冻结，必可成交。
            return requestedQty;
        }

        @Override
        public Trade onFill(Order taker, Order maker, long price, long qty) {
            long notional = price * qty;
            String buyer = taker.getSide() == Side.BUY ? taker.getAccountId() : maker.getAccountId();
            String seller = taker.getSide() == Side.SELL ? taker.getAccountId() : maker.getAccountId();
            Order buyOrder = taker.getSide() == Side.BUY ? taker : maker;

            runWithOrderedLocks(buyer, seller, () -> {
                // 买方：冻结按挂单价预算释放（限价单），现金按实际成交价扣减。
                accounts.get(buyer).settleBuy(notional, buyOrder.getPrice() * qty);
                positions.get(buyer).computeIfAbsent(taker.getSymbol(), s -> new Position())
                        .applyBuyFill(qty);
                // 卖方：从卖出冻结中扣减持仓，所得现金入账。
                positions.get(seller).get(taker.getSymbol()).applySellFill(qty);
                accounts.get(seller).applySellFill(notional);
            });

            Trade trade = new Trade(tradeIdSeq.incrementAndGet(), taker.getSymbol(), price, qty,
                    taker.getId(), maker.getId(),
                    taker.getAccountId(), maker.getAccountId(), taker.getSide(),
                    System.currentTimeMillis());
            tradesBySymbol.get(taker.getSymbol()).add(trade);
            log.info("成交: takerOrder={}, makerOrder={}, price={}, qty={}, notional={}, {}<->{}",
                    taker.getId(), maker.getId(), price, qty, notional,
                    taker.getAccountId(), maker.getAccountId());
            return trade;
        }
    }

    // ---------------------------------------------------------------------
    // 锁与串行执行基础设施
    // ---------------------------------------------------------------------

    private ReentrantLock lockOf(String accountId) {
        return accountLocks.computeIfAbsent(accountId, k -> new ReentrantLock());
    }

    private void withLock(String accountId, Runnable action) {
        ReentrantLock lock = lockOf(accountId);
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    private void withLockQuietly(String accountId, Runnable action) {
        withLock(accountId, action);
    }

    private <T> T withLockResult(String accountId, java.util.function.Supplier<T> supplier) {
        ReentrantLock lock = lockOf(accountId);
        lock.lock();
        try {
            return supplier.get();
        } finally {
            lock.unlock();
        }
    }

    /** 两个账户可能相同：相同时只加一次锁；不同时按 id 升序加锁，杜绝环路死锁。 */
    private void runWithOrderedLocks(String a, String b, Runnable action) {
        if (a.equals(b)) {
            withLock(a, action);
            return;
        }
        String first = a.compareTo(b) <= 0 ? a : b;
        String second = a.compareTo(b) <= 0 ? b : a;
        ReentrantLock l1 = lockOf(first);
        ReentrantLock l2 = lockOf(second);
        l1.lock();
        l2.lock();
        try {
            action.run();
        } finally {
            l2.unlock();
            l1.unlock();
        }
    }

    private <T> T runOnSymbol(String symbol, java.util.function.Supplier<T> task) {
        ExecutorService executor;
        synchronized (executors) {
            executor = executors.get(symbol);
            if (executor == null || executor.isShutdown()) {
                executor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "matching-" + symbol);
                    t.setDaemon(true);
                    return t;
                });
                executors.put(symbol, executor);
            }
        }
        try {
            Future<T> future = executor.submit(task::get);
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TradingException(RejectReason.SYSTEM_ERROR, "撮合被中断");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TradingException te) {
                throw te;
            }
            if (cause instanceof RuntimeException re && re.getCause() instanceof TradingException te2) {
                throw te2;
            }
            log.error("撮合内部异常", cause);
            throw new TradingException(RejectReason.SYSTEM_ERROR, "系统繁忙，请稍后重试");
        }
    }

    private void requireSymbol(String symbol) {
        if (!symbols.contains(symbol)) {
            throw reject(RejectReason.INVALID_SYMBOL, "未知标的: " + symbol);
        }
    }

    private TradingException reject(RejectReason reason, String message) {
        return new TradingException(reason, message);
    }

    public void shutdown() {
        if (closed.compareAndSet(false, true)) {
            // 等待每个 symbol 队列中已提交的任务跑完，避免关闭时丢失最后几笔撮合。
            for (Map.Entry<String, ExecutorService> e :
                    new java.util.ArrayList<>(executors.entrySet())) {
                ExecutorService executor = e.getValue();
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    executor.shutdownNow();
                }
                executors.remove(e.getKey(), executor);
            }
        }
    }

    /** 校验后的标准下单参数。 */
    private static final class ParsedOrder {
        String accountId;
        String symbol;
        Side side;
        OrderType type;
        long price;
        long quantity;
    }
}
