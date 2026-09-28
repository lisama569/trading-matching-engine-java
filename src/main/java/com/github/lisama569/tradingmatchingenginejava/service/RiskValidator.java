package com.github.lisama569.tradingmatchingenginejava.service;

import com.github.lisama569.tradingmatchingenginejava.domain.OrderType;
import com.github.lisama569.tradingmatchingenginejava.domain.RejectReason;
import com.github.lisama569.tradingmatchingenginejava.domain.Side;
import com.github.lisama569.tradingmatchingenginejava.dto.PlaceOrderRequest;
import com.github.lisama569.tradingmatchingenginejava.exception.TradingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 下单前的静态风控（无状态、可在任意线程执行）：
 *   - 必填字段；
 *   - side/type 枚举合法；
 *   - 数量为正、限价单价格为正。
 * 账户/标的是否存在、资金持仓是否充足、限额等依赖内存状态的校验由 MatchingEngine 完成，
 * 两层都拒绝时原因码一致、可区分。
 */
@Component
public class RiskValidator {

    private static final Logger log = LoggerFactory.getLogger(RiskValidator.class);

    public void validate(PlaceOrderRequest request) {
        if (request == null) {
            throw reject(RejectReason.SYSTEM_ERROR, "请求体为空");
        }
        if (isBlank(request.getAccountId())) {
            throw reject(RejectReason.INVALID_ACCOUNT, "accountId 为空");
        }
        if (isBlank(request.getSymbol())) {
            throw reject(RejectReason.INVALID_SYMBOL, "symbol 为空");
        }
        parseSide(request.getSide());
        OrderType type = parseType(request.getType());

        if (request.getQuantity() == null) {
            throw reject(RejectReason.INVALID_QUANTITY, "quantity 为空");
        }
        if (request.getQuantity() <= 0) {
            throw reject(RejectReason.INVALID_QUANTITY,
                    "quantity 必须为正整数（股）: " + request.getQuantity());
        }
        if (type == OrderType.LIMIT) {
            if (request.getPrice() == null) {
                throw reject(RejectReason.INVALID_PRICE, "限价单必须提供 price（分）");
            }
            if (request.getPrice() <= 0) {
                throw reject(RejectReason.INVALID_PRICE,
                        "price 必须为正整数（分）: " + request.getPrice());
            }
        }
        log.info("风控静态校验通过: account={}, symbol={}, side={}, type={}, price={}, qty={}",
                request.getAccountId(), request.getSymbol(), request.getSide(),
                request.getType(), request.getPrice(), request.getQuantity());
    }

    private Side parseSide(String side) {
        if (isBlank(side)) {
            throw reject(RejectReason.INVALID_SIDE, "side 为空");
        }
        try {
            return Side.valueOf(side.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw reject(RejectReason.INVALID_SIDE, "未知方向（仅支持 BUY/SELL）: " + side);
        }
    }

    private OrderType parseType(String type) {
        if (isBlank(type)) {
            throw reject(RejectReason.INVALID_ORDER_TYPE, "type 为空");
        }
        try {
            return OrderType.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw reject(RejectReason.INVALID_ORDER_TYPE, "未知订单类型（仅支持 LIMIT/MARKET）: " + type);
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private TradingException reject(RejectReason reason, String message) {
        log.info("风控静态校验拒绝: reason={}, detail={}", reason, message);
        return new TradingException(reason, message);
    }
}
