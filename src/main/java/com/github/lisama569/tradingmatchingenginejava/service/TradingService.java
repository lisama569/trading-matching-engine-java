package com.github.lisama569.tradingmatchingenginejava.service;

import com.github.lisama569.tradingmatchingenginejava.domain.Order;
import com.github.lisama569.tradingmatchingenginejava.domain.RejectReason;
import com.github.lisama569.tradingmatchingenginejava.dto.AccountSnapshot;
import com.github.lisama569.tradingmatchingenginejava.dto.CancelOrderRequest;
import com.github.lisama569.tradingmatchingenginejava.dto.CancelResponse;
import com.github.lisama569.tradingmatchingenginejava.dto.OrderResponse;
import com.github.lisama569.tradingmatchingenginejava.dto.PlaceOrderRequest;
import com.github.lisama569.tradingmatchingenginejava.engine.MatchingEngine;
import com.github.lisama569.tradingmatchingenginejava.exception.TradingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 对外交易服务门面。
 * 职责边界：
 *   - 入参静态风控（RiskValidator）；
 *   - 调用 MatchingEngine 完成状态校验、冻结与撮合；
 *   - 把业务拒绝转成 success=false + rejectReason 的正常响应；
 *   - 任何未预期异常统一收敛为 SYSTEM_ERROR，堆栈只进日志、不透传给调用方。
 */
@Service
public class TradingService {

    private static final Logger log = LoggerFactory.getLogger(TradingService.class);

    private final MatchingEngine engine;
    private final RiskValidator riskValidator;

    public TradingService(MatchingEngine engine, RiskValidator riskValidator) {
        this.engine = engine;
        this.riskValidator = riskValidator;
    }

    public OrderResponse placeOrder(PlaceOrderRequest request) {
        try {
            riskValidator.validate(request);
            Order order = engine.placeOrder(request);
            log.info("下单成功 orderId={}, status={}, filled={}, remaining={}, avgPrice={}",
                    order.getId(), order.getStatus(), order.getFilledQty(),
                    order.getRemainingQty(), order.getAvgFillPrice());
            return OrderResponse.ok(order);
        } catch (TradingException e) {
            log.info("下单被拒绝 reason={}, detail={}", e.getReason(), e.getMessage());
            return OrderResponse.fail(e.getReason(), e.getMessage());
        } catch (Exception e) {
            log.error("下单发生未预期异常", e);
            return OrderResponse.fail(RejectReason.SYSTEM_ERROR, "系统繁忙，请稍后重试");
        }
    }

    public CancelResponse cancel(CancelOrderRequest request) {
        try {
            Order order = engine.cancel(request);
            log.info("撤单完成 orderId={}, status={}", order.getId(), order.getStatus());
            return CancelResponse.ok(order);
        } catch (TradingException e) {
            log.info("撤单被拒绝 reason={}, detail={}", e.getReason(), e.getMessage());
            return CancelResponse.fail(e.getReason(), e.getMessage());
        } catch (Exception e) {
            log.error("撤单发生未预期异常", e);
            return CancelResponse.fail(RejectReason.SYSTEM_ERROR, "系统繁忙，请稍后重试");
        }
    }

    public OrderResponse queryOrder(long orderId) {
        try {
            Order order = engine.getOrder(orderId);
            if (order == null) {
                return OrderResponse.fail(RejectReason.ORDER_NOT_FOUND,
                        "订单不存在: orderId=" + orderId);
            }
            return OrderResponse.ok(order);
        } catch (Exception e) {
            log.error("查询订单发生未预期异常", e);
            return OrderResponse.fail(RejectReason.SYSTEM_ERROR, "系统繁忙，请稍后重试");
        }
    }

    public AccountSnapshot accountSnapshot(String accountId) {
        try {
            Map<String, Long> balance = engine.accountBalanceSnapshot(accountId);
            if (balance == null) {
                return null;
            }
            return new AccountSnapshot(accountId,
                    balance.get("cash"), balance.get("frozenCash"), balance.get("availableCash"),
                    engine.allPositionsSnapshot(accountId));
        } catch (Exception e) {
            log.error("查询账户发生未预期异常", e);
            return null;
        }
    }
}
