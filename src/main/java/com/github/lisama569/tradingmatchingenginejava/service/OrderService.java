package com.github.lisama569.tradingmatchingenginejava.service;

import com.github.lisama569.tradingmatchingenginejava.common.TradingException;
import com.github.lisama569.tradingmatchingenginejava.engine.MatchingEngine;
import com.github.lisama569.tradingmatchingenginejava.model.Account;
import com.github.lisama569.tradingmatchingenginejava.model.InstrumentSpec;
import com.github.lisama569.tradingmatchingenginejava.model.Order;
import com.github.lisama569.tradingmatchingenginejava.model.OrderStatus;
import com.github.lisama569.tradingmatchingenginejava.model.OrderType;
import com.github.lisama569.tradingmatchingenginejava.model.Position;
import com.github.lisama569.tradingmatchingenginejava.model.RejectCode;
import com.github.lisama569.tradingmatchingenginejava.model.Side;
import com.github.lisama569.tradingmatchingenginejava.model.Trade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 下单编排：参数风控 -&gt; 账户锁内资金/持仓校验与冻结 -&gt; 撮合引擎。
 *
 * <h3>冻结规则</h3>
 * <ul>
 *     <li>限价买入：按限价全额冻结 {@code price*qty}；</li>
 *     <li>市价买入：按合约保护价 {@code marketMaxPrice} 全额冻结，
 *     实际成交差价在 IOC 终结 / 成交后释放，因此可用资金不会被意外扣穿；</li>
 *     <li>卖出：按数量冻结持仓。</li>
 * </ul>
 * 冻结在申请标的锁之前完成并释放账户锁，保证锁顺序只有 "标的锁 -&gt; 账户锁" 一个方向。
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final MatchingEngine engine;
    private final AccountService accountService;

    public OrderService(MatchingEngine engine, AccountService accountService) {
        this.engine = engine;
        this.accountService = accountService;
    }

    /**
     * 提交委托。
     *
     * @return 撮合后的订单（含成交明细，从 engine 返回值取）
     */
    public OrderResult submit(long accountId, String symbol, String sideRaw, String typeRaw,
                              Long price, long qty) {
        log.info("收到下单请求: account={}, symbol={}, side={}, type={}, price={}, qty={}",
                accountId, symbol, sideRaw, typeRaw, price, qty);

        Side side = parseSide(sideRaw);
        OrderType type = parseType(typeRaw);
        InstrumentSpec spec = engine.getInstrument(symbol); // 标的不存在 -> INSTRUMENT_NOT_FOUND
        Account account = accountService.get(accountId);    // 账户不存在 -> ACCOUNT_NOT_FOUND
        validateCommon(spec, qty);
        long effectivePrice = validatePrice(spec, side, type, price);

        // 账户锁内：check-then-freeze 原子完成，杜绝并发下冻结为负。
        long orderId = engine.nextOrderId();
        if (side == Side.BUY) {
            long freezeAmount = Math.multiplyExact(effectivePrice, qty);
            if (freezeAmount > spec.maxOrderNotional()) {
                throw reject(RejectCode.NOTIONAL_EXCEEDS_LIMIT,
                        "单笔委托金额超限: notional=" + freezeAmount + ", max=" + spec.maxOrderNotional());
            }
            Boolean frozen = accountService.withAccount(accountId, a -> a.freezeCash(freezeAmount));
            if (!frozen) {
                throw reject(RejectCode.INSUFFICIENT_CASH,
                        "可用资金不足: need=" + freezeAmount + ", available=" + account.availableCash());
            }
            log.info("风控通过并冻结资金: account={}, order={}, freezeCash={}", accountId, orderId, freezeAmount);
        } else {
            Boolean frozen = accountService.withAccount(accountId, a -> a.positionOf(symbol).freeze(qty));
            if (!frozen) {
                long available = accountService.withAccount(accountId, a -> a.positionOf(symbol).available());
                throw reject(RejectCode.INSUFFICIENT_POSITION,
                        "可用持仓不足: need=" + qty + ", available=" + available);
            }
            log.info("风控通过并冻结持仓: account={}, order={}, freezeQty={}", accountId, orderId, qty);
        }

        Order order = new Order(orderId, orderId, accountId, symbol, side, type,
                type == OrderType.LIMIT ? effectivePrice : null, qty, System.currentTimeMillis());
        order.initFreeze(side == Side.BUY ? effectivePrice * qty : 0,
                side == Side.SELL ? qty : 0);

        List<Trade> trades;
        try {
            trades = engine.placeOrder(order);
        } catch (RuntimeException e) {
            // 撮合内部异常：为保证账务自洽，尽力释放该订单全部冻结，再以兜底错误对外。
            log.error("撮合内部异常，尝试回滚冻结: orderId={}", orderId, e);
            safeRollback(order);
            throw new TradingException(RejectCode.SYSTEM_ERROR, "系统繁忙，请稍后重试", e);
        }

        log.info("下单处理完成: order={}, status={}, filled={}/{}, trades={}",
                order.getId(), order.getStatus(), order.getFilledQty(), order.getOrigQty(), trades.size());
        return new OrderResult(order, trades);
    }

    /** 撤单：委托引擎在标的锁内终结并释放冻结。 */
    public Order cancel(long accountId, long orderId) {
        log.info("收到撤单请求: account={}, order={}", accountId, orderId);
        Order order = engine.cancel(orderId, accountId);
        log.info("撤单完成: order={}, status={}", order.getId(), order.getStatus());
        return order;
    }

    private void safeRollback(Order order) {
        // 正常路径下冻结只会由引擎在标的锁内释放；此处仅在引擎抛异常时兜底，
        // 若订单已经被引擎部分处理，则只释放订单上仍记账的冻结额，避免重复释放。
        try {
            // 引擎异常时状态语义不可靠，直接走一次撤单式终结；已被终结则忽略。
            if (order.isActive()) {
                engine.cancel(order.getId(), order.getAccountId());
            }
        } catch (RuntimeException ex) {
            log.error("冻结回滚失败，需要人工核对: orderId={}", order.getId(), ex);
        }
    }

    // ---------------- 风控校验 ----------------

    private void validateCommon(InstrumentSpec spec, long qty) {
        if (qty <= 0) {
            throw reject(RejectCode.INVALID_QTY, "委托数量必须为正整数: qty=" + qty);
        }
        if (spec.lotSize() > 0 && qty % spec.lotSize() != 0) {
            throw reject(RejectCode.INVALID_QTY,
                    "委托数量必须是最小交易单位 " + spec.lotSize() + " 的整数倍: qty=" + qty);
        }
        if (qty > spec.maxOrderQty()) {
            throw reject(RejectCode.QTY_EXCEEDS_LIMIT,
                    "单笔委托数量超限: qty=" + qty + ", max=" + spec.maxOrderQty());
        }
    }

    /**
     * 校验价格并返回用于冻结的有效价：限价单用限价，市价买入用保护价，市价卖出用 0（不冻结资金）。
     */
    private long validatePrice(InstrumentSpec spec, Side side, OrderType type, Long price) {
        if (type == OrderType.LIMIT) {
            if (price == null || price <= 0) {
                throw reject(RejectCode.INVALID_PRICE, "限价单价格必须为正整数(分): price=" + price);
            }
            if (!spec.isPriceOnTick(price)) {
                throw reject(RejectCode.INVALID_PRICE,
                        "价格必须是最小变动单位 " + spec.tickSize() + " 的整数倍: price=" + price);
            }
            if (price < spec.lowerLimitPrice() || price > spec.upperLimitPrice()) {
                throw reject(RejectCode.PRICE_OUT_OF_BAND,
                        "价格超出涨跌停带 [" + spec.lowerLimitPrice() + "," + spec.upperLimitPrice()
                                + "]: price=" + price);
            }
            return price;
        }
        // 市价单：买入按保护价冻结资金；卖出只冻结持仓。
        return side == Side.BUY ? spec.marketMaxPrice() : 0L;
    }

    private Side parseSide(String raw) {
        if (raw == null) {
            throw reject(RejectCode.INVALID_SIDE, "缺少买卖方向");
        }
        try {
            return Side.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw reject(RejectCode.INVALID_SIDE, "非法买卖方向: " + raw);
        }
    }

    private OrderType parseType(String raw) {
        if (raw == null) {
            throw reject(RejectCode.INVALID_ORDER_TYPE, "缺少订单类型");
        }
        try {
            return OrderType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw reject(RejectCode.INVALID_ORDER_TYPE, "非法订单类型: " + raw);
        }
    }

    private TradingException reject(RejectCode code, String message) {
        log.info("风控拒绝: code={}, reason={}", code, message);
        return new TradingException(code, message);
    }

    /** 下单结果：订单 + 本次产生的成交。 */
    public record OrderResult(Order order, List<Trade> trades) {
    }

    /** 供测试/查询使用：按 id 取订单。 */
    public Order getOrder(long orderId) {
        Order o = engine.getOrder(orderId);
        if (o == null) {
            throw new TradingException(RejectCode.ORDER_NOT_FOUND, "订单不存在: orderId=" + orderId);
        }
        return o;
    }

    /** 查询订单是否已终结。 */
    public boolean isTerminal(long orderId) {
        Order o = engine.getOrder(orderId);
        return o != null && o.getStatus() != OrderStatus.NEW && o.getStatus() != OrderStatus.PARTIALLY_FILLED;
    }
}
