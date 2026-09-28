package com.github.lisama569.tradingmatchingenginejava.engine;

import com.github.lisama569.tradingmatchingenginejava.domain.Order;
import com.github.lisama569.tradingmatchingenginejava.domain.Trade;

/**
 * 撮合回调：OrderBook 负责按价格/时间顺序挑选对手单，资金与持仓结算交给引擎。
 * 拆出回调可以保证「撮合顺序」与「记账」两件事互不耦合、各自可测。
 */
interface FillSink {

    /**
     * 在正式成交前询问引擎本笔最多允许成交的数量（0..requestedQty）。
     * 主要用于市价买单：可用资金只够买部分数量时，引擎按可整股购买的数量截短；
     * 一股都买不起（卖单为冻结不足，理论上不会发生）返回 0，订单簿随即终止撮合，
     * 且不会扣减任何一方数量。
     */
    long allowedQty(Order taker, Order maker, long price, long requestedQty);

    /**
     * 提交一笔成交：引擎在此完成买卖双方资金/持仓结算，生成并返回 Trade。
     * 订单本身的成交数量与状态由 OrderBook 在回调后统一更新。
     */
    Trade onFill(Order taker, Order maker, long price, long qty);
}
