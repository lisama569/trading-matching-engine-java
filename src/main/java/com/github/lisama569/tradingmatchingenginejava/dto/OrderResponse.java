package com.github.lisama569.tradingmatchingenginejava.dto;

import com.github.lisama569.tradingmatchingenginejava.domain.Order;
import com.github.lisama569.tradingmatchingenginejava.domain.RejectReason;

/** 下单/查询结果：成功返回订单快照（含成交后状态），失败返回 rejectReason。 */
public class OrderResponse {

    private boolean success;
    private String rejectReason;
    private String message;
    private Order order;

    public OrderResponse() {
    }

    public static OrderResponse ok(Order order) {
        OrderResponse r = new OrderResponse();
        r.success = true;
        r.order = order;
        return r;
    }

    public static OrderResponse fail(RejectReason reason, String message) {
        OrderResponse r = new OrderResponse();
        r.success = false;
        r.rejectReason = reason.name();
        r.message = message;
        return r;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Order getOrder() { return order; }
    public void setOrder(Order order) { this.order = order; }
}
