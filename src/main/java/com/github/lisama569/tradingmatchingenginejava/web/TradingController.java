package com.github.lisama569.tradingmatchingenginejava.web;

import com.github.lisama569.tradingmatchingenginejava.domain.RejectReason;
import com.github.lisama569.tradingmatchingenginejava.dto.AccountSnapshot;
import com.github.lisama569.tradingmatchingenginejava.dto.CancelOrderRequest;
import com.github.lisama569.tradingmatchingenginejava.dto.CancelResponse;
import com.github.lisama569.tradingmatchingenginejava.dto.OrderResponse;
import com.github.lisama569.tradingmatchingenginejava.dto.PlaceOrderRequest;
import com.github.lisama569.tradingmatchingenginejava.service.TradingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 交易 REST 接口。
 * 业务失败也返回 HTTP 200，由 body.success / rejectReason 区分；
 * 框架层异常（如请求体解析失败）由 #handleException 兜底，绝不把堆栈透传给外部。
 */
@RestController
@RequestMapping("/api")
public class TradingController {

    private final TradingService tradingService;

    public TradingController(TradingService tradingService) {
        this.tradingService = tradingService;
    }

    @PostMapping("/orders")
    public OrderResponse place(@RequestBody(required = false) PlaceOrderRequest request) {
        return tradingService.placeOrder(request);
    }

    @PostMapping("/orders/cancel")
    public CancelResponse cancel(@RequestBody(required = false) CancelOrderRequest request) {
        if (request == null) {
            return CancelResponse.fail(RejectReason.SYSTEM_ERROR, "请求体为空");
        }
        return tradingService.cancel(request);
    }

    @GetMapping("/orders/{orderId}")
    public OrderResponse query(@PathVariable long orderId) {
        return tradingService.queryOrder(orderId);
    }

    @GetMapping("/accounts/{accountId}")
    public ResponseEntity<AccountSnapshot> account(@PathVariable String accountId) {
        AccountSnapshot snapshot = tradingService.accountSnapshot(accountId);
        return snapshot == null
                ? ResponseEntity.status(404).build()
                : ResponseEntity.ok(snapshot);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        // 参数序列化失败等：对外只给稳定错误码，内部细节仅记录日志。
        return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "rejectReason", RejectReason.SYSTEM_ERROR.name(),
                "message", "请求处理失败，请检查输入"));
    }
}
