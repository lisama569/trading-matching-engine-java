package com.github.lisama569.tradingmatchingenginejava.engine;

import com.github.lisama569.tradingmatchingenginejava.model.Order;
import com.github.lisama569.tradingmatchingenginejava.model.Side;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 单标的限价撮合簿。
 * <p>撮合顺序：
 * <ol>
 *     <li><b>价格优先</b>：买盘取最高买价、卖盘取最低卖价（TreeMap 维护有序价位）；</li>
 *     <li><b>同价时间优先</b>：同一价位是 FIFO 队列，按订单进入撮合簿的先后排列，
 *     与全局序列号单调一致（先进入的序列号一定更小）。</li>
 * </ol>
 * 非线程安全：所有访问都必须在 {@link MatchingEngine} 的标的锁内进行。
 * 撤单采用惰性清除：终结订单在轮询到队首时被跳过；撤单时也会做一次尽力即时删除。
 */
final class OrderBook {

    /** key = 价格；买盘价格降序，卖盘价格升序。 */
    private final TreeMap<Long, ArrayDeque<Order>> bids = new TreeMap<>(java.util.Comparator.reverseOrder());
    private final TreeMap<Long, ArrayDeque<Order>> asks = new TreeMap<>();

    void addResting(Order order) {
        treeOf(order.getSide())
                .computeIfAbsent(order.getLimitPrice(), p -> new ArrayDeque<>())
                .addLast(order);
    }

    /**
     * 取出对手方最优的一笔仍活跃的订单；没有则返回 null。
     * 惰性丢弃队列中已经终结（成交/撤销）的订单。
     */
    Order pollBestActive(Side takerSide) {
        TreeMap<Long, ArrayDeque<Order>> opposite = takerSide == Side.BUY ? asks : bids;
        while (!opposite.isEmpty()) {
            Map.Entry<Long, ArrayDeque<Order>> level = opposite.firstEntry();
            ArrayDeque<Order> queue = level.getValue();
            while (!queue.isEmpty()) {
                Order head = queue.peekFirst();
                if (head.isActive()) {
                    return head;
                }
                queue.pollFirst(); // 惰性清除已终结订单
            }
            opposite.remove(level.getKey());
        }
        return null;
    }

    /** 成交后若 maker 已终结，将其从队首移除（成交的 maker 一定在队首）。 */
    void removeIfHeadFilled(Side makerSide) {
        TreeMap<Long, ArrayDeque<Order>> tree = treeOf(makerSide);
        Map.Entry<Long, ArrayDeque<Order>> level = tree.firstEntry();
        if (level == null) {
            return;
        }
        ArrayDeque<Order> queue = level.getValue();
        Order head = queue.peekFirst();
        if (head != null && !head.isActive()) {
            queue.pollFirst();
            if (queue.isEmpty()) {
                tree.remove(level.getKey());
            }
        }
    }

    /** 撤单时尽力即时删除，使行情深度立即反映撤单结果。 */
    void removeOrder(Order order) {
        TreeMap<Long, ArrayDeque<Order>> tree = treeOf(order.getSide());
        ArrayDeque<Order> queue = tree.get(order.getLimitPrice());
        if (queue != null) {
            queue.remove(order);
            if (queue.isEmpty()) {
                tree.remove(order.getLimitPrice());
            }
        }
    }

    /** 对手盘是否存在（不考虑价格是否可成交，只看队列是否非空）。 */
    boolean hasOppositeLiquidity(Side takerSide) {
        return pollBestActive(takerSide) != null;
    }

    /** 对手方最优价；无对手盘返回 null。 */
    Long bestOppositePrice(Side takerSide) {
        TreeMap<Long, ArrayDeque<Order>> opposite = takerSide == Side.BUY ? asks : bids;
        return opposite.isEmpty() ? null : opposite.firstKey();
    }

    /**
     * 行情快照：每个价位汇总 [价格, 总挂单量, 活跃挂单量]。
     * 返回元素为 int[]{price, totalQty, activeQty}，activeQty 已排除终结订单。
     */
    List<long[]> snapshot(Side side, int maxLevels) {
        TreeMap<Long, ArrayDeque<Order>> tree = treeOf(side);
        List<long[]> result = new ArrayList<>();
        for (Map.Entry<Long, ArrayDeque<Order>> e : tree.entrySet()) {
            long total = 0;
            long active = 0;
            for (Order o : e.getValue()) {
                long remaining = o.remainingQty();
                total += remaining;
                if (o.isActive()) {
                    active += remaining;
                }
            }
            if (active > 0) {
                result.add(new long[]{e.getKey(), total, active});
                if (result.size() >= maxLevels) {
                    break;
                }
            }
        }
        return result;
    }

    private TreeMap<Long, ArrayDeque<Order>> treeOf(Side side) {
        return side == Side.BUY ? bids : asks;
    }
}
