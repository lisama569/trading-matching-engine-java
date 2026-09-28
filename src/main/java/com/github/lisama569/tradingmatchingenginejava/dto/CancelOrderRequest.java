package com.github.lisama569.tradingmatchingenginejava.dto;

/** 撤单请求。 */
public class CancelOrderRequest {
    private String accountId;
    private Long orderId;

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
}
