package com.github.lisama569.tradingmatchingenginejava.dto;

import com.github.lisama569.tradingmatchingenginejava.domain.Order;
import com.github.lisama569.tradingmatchingenginejava.domain.RejectReason;

/** 撤单结果：成功时带订单最终快照。 */
public class CancelResponse {

    private boolean success;
    private String rejectReason;
    private String message;
    private Long orderId;

    public CancelResponse() {
    }

    public static CancelResponse ok(Order order) {
        CancelResponse r = new CancelResponse();
        r.success = true;
        r.orderId = order.getId();
        r.message = "撤单成功";
        return r;
    }

    public static CancelResponse fail(RejectReason reason, String message) {
        CancelResponse r = new CancelResponse();
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
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
}
