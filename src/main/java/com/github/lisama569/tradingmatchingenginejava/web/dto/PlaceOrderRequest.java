package com.github.lisama569.tradingmatchingenginejava.web.dto;

/**
 * 下单请求（JSON）。
 * <p>价格单位为最小货币单位（分）的<b>整数</b>，数量为整数股。
 * 例如 10.05 元请传 {@code "price":"1005"}。避免浮点误差导致的撮合不确定。
 */
public class PlaceOrderRequest {

    private Long accountId;
    private String symbol;
    /** BUY / SELL */
    private String side;
    /** LIMIT / MARKET */
    private String type;
    /** 限价单必填且为正整数（分）；市价单忽略 */
    private Long price;
    private Long qty;

    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Long getPrice() { return price; }
    public void setPrice(Long price) { this.price = price; }
    public Long getQty() { return qty; }
    public void setQty(Long qty) { this.qty = qty; }
}
