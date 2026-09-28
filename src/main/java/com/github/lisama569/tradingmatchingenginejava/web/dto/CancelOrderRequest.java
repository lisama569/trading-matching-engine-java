package com.github.lisama569.tradingmatchingenginejava.web.dto;

/** 撤单请求（JSON）。必须同时提供账户 id 与订单 id，只允许撤本人的单。 */
public class CancelOrderRequest {

    private Long accountId;
    private Long orderId;

    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
}
