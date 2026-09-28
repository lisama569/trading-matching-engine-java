package com.github.lisama569.tradingmatchingenginejava.dto;

/** 下单请求。price 单位：分；限价单必填正数，市价单忽略。 */
public class PlaceOrderRequest {
    private String accountId;
    private String symbol;
    private String side;
    private String type;
    private Long price;
    private Long quantity;

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Long getPrice() { return price; }
    public void setPrice(Long price) { this.price = price; }
    public Long getQuantity() { return quantity; }
    public void setQuantity(Long quantity) { this.quantity = quantity; }
}
