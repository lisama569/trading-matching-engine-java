package com.github.lisama569.tradingmatchingenginejava.engine;

import com.github.lisama569.tradingmatchingenginejava.domain.Order;
import com.github.lisama569.tradingmatchingenginejava.domain.OrderStatus;
import com.github.lisama569.tradingmatchingenginejava.domain.Side;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 单只标的的订单簿。
 *
 * 撮合优先级（确定、可复现）：
 *   1) 价格优先：买单取对手卖盘最低价，卖单取对手买盘最高价；
 *   2) 同价时间优先：同一价位按入队序号 sequence（FIFO）成交；
 *   3) 成交价 = 挂单方（maker）价格。
 *
 * 数据结构（保证挂单量增大不退化）：
 *   - bids/asks 为 TreeMap 分价位，取最优价 O(log n)；
 *   - 每个价位一个 FIFO 队列；
 *   - index 为 orderId -> Order 哈希索引，撤单/查询 O(1)；
 *   - 撤单采用惰性删除：只标记 CANCELED，撮合扫到队首时弹出，
 *     因而撤单 O(1)、入队 O(log n)，撮合只与实际成交/清理量有关。
 *
 * 线程模型：全部方法只在该 symbol 的单线程执行器中调用，无需同步。
 */
class OrderBook {

    /** 卖盘：价格升序。 */
    private final NavigableMap<Long, ArrayDeque<Order>> asks = new TreeMap<>();
    /** 买盘：价格升序，取 lastEntry 得到最高价。 */
    private final NavigableMap<Long, ArrayDeque<Order>> bids = new TreeMap<>();
    /** orderId -> 挂单（含尚未被惰性清理的已撤单），保证撤单 O(1)。 */
    private final HashMap<Long, Order> index = new HashMap<>();

    /**
     * 用 taker 单与对手盘撮合，直到：
     *  - taker 余量为 0；
     *  - 对手盘为空 / 价格不再满足限价；
     *  - sink.allowedQty 返回 0（市价单资金或持仓耗尽）。
     * 本方法只负责挑选与数量扣减，结算由 sink 完成。
     */
    void match(Order taker, FillSink sink) {
        boolean buy = taker.getSide() == Side.BUY;
        NavigableMap<Long, ArrayDeque<Order>> opposite = buy ? asks : bids;

        while (taker.getRemainingQty() > 0 && !opposite.isEmpty()) {
            Map.Entry<Long, ArrayDeque<Order>> levelEntry =
                    buy ? opposite.firstEntry() : opposite.lastEntry();
            long levelPrice = levelEntry.getKey();

            // 限价单价格条件：买单价 >= 卖一价；卖单价 <= 买一价。
            if (!priceAcceptable(taker, levelPrice)) {
                break;
            }

            ArrayDeque<Order> queue = levelEntry.getValue();
            pruneHead(queue, levelPrice, opposite);
            if (queue.isEmpty()) {
                continue;
            }

            Order maker = queue.peek();
            long wanted = Math.min(taker.getRemainingQty(), maker.getRemainingQty());

            // 让引擎做资金/持仓的最终确认与截短（市价单边界）。
            long qty = sink.allowedQty(taker, maker, levelPrice, wanted);
            if (qty <= 0) {
                break;
            }

            sink.onFill(taker, maker, levelPrice, qty);
            maker.applyFill(levelPrice, qty);
            taker.applyFill(levelPrice, qty);

            if (maker.getRemainingQty() == 0) {
                // maker 完全成交，弹出队首并移出索引；若该价位清空则移除价位。
                queue.poll();
                index.remove(maker.getId());
                if (queue.isEmpty()) {
                    opposite.remove(levelPrice);
                }
            }
        }
    }

    /** 入簿：只接收还有余量且未结束的限价单。 */
    boolean add(Order order) {
        if (order.getRemainingQty() <= 0 || order.isFinished()) {
            return false;
        }
        bookOf(order.getSide())
                .computeIfAbsent(order.getPrice(), p -> new ArrayDeque<>())
                .addLast(order);
        index.put(order.getId(), order);
        return true;
    }

    /**
     * 撤单：哈希索引 O(1) 定位，惰性标记 CANCELED，下次撮合扫到队首时清理并移出索引。
     * 返回被撤订单；簿中不存在返回 null；订单已结束（全部成交）原样返回由调用方判定。
     */
    Order remove(long orderId) {
        Order order = index.remove(orderId);
        if (order != null && !order.isFinished()) {
            // 队列中不立即摘除（O(n)），仅靠状态位在下次撮合扫到队首时惰性清理；
            // 但索引立即摘除，保证撤单后 get/二次撤单都查不到，索引也不堆积。
            order.markCanceled();
        }
        return order;
    }

    /** 直接查簿中的订单（含尚未被惰性清理的已撤单），O(1)。 */
    Order get(long orderId) {
        return index.get(orderId);
    }

    /** 对手盘最优价；无对手盘返回 -1（区别于合法非负价格）。 */
    long bestOppositePrice(Order taker) {
        NavigableMap<Long, ArrayDeque<Order>> opposite = bookOf(opposite(taker.getSide()));
        while (true) {
            if (opposite.isEmpty()) {
                return -1L;
            }
            Map.Entry<Long, ArrayDeque<Order>> e =
                    taker.getSide() == Side.BUY ? opposite.firstEntry() : opposite.lastEntry();
            int sizeBefore = e.getValue().size();
            pruneHead(e.getValue(), e.getKey(), opposite);
            // 若队首价位被整体移除则继续看下一价位；否则该价位已清理干净，其价即最优价。
            if (opposite.containsKey(e.getKey())) {
                return e.getKey();
            }
            if (sizeBefore == 0) {
                return -1L;
            }
        }
    }

    int askLevels() { return asks.size(); }
    int bidLevels() { return bids.size(); }
    int indexedOrders() { return index.size(); }

    private NavigableMap<Long, ArrayDeque<Order>> bookOf(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    private Side opposite(Side side) {
        return side == Side.BUY ? Side.SELL : Side.BUY;
    }

    private boolean priceAcceptable(Order taker, long makerPrice) {
        return switch (taker.getType()) {
            case MARKET -> true;
            case LIMIT -> taker.getSide() == Side.BUY
                    ? taker.getPrice() >= makerPrice
                    : taker.getPrice() <= makerPrice;
        };
    }

    /**
     * 弹出价位队列队首所有已撤销的惰性删除订单，同步移出哈希索引；
     * 价位清空时移除价位。
     */
    private void pruneHead(ArrayDeque<Order> queue, long price,
                           NavigableMap<Long, ArrayDeque<Order>> book) {
        while (!queue.isEmpty() && queue.peek().getStatus() == OrderStatus.CANCELED) {
            queue.poll();
        }
        if (queue.isEmpty()) {
            book.remove(price);
        }
    }
}
