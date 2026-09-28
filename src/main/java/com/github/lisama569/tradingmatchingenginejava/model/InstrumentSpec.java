package com.github.lisama569.tradingmatchingenginejava.model;

/**
 * 标的合约参数（内存静态配置）。金额单位为最小货币单位（分），数量单位为股/份。
 *
 * @param symbol          标的代码
 * @param lotSize         最小交易单位，下单数量必须是其整数倍
 * @param tickSize        最小价格变动单位
 * @param lowerLimitPrice 限价单价格带下限（含）
 * @param upperLimitPrice 限价单价格带上限（含）
 * @param maxOrderQty     单笔委托最大数量
 * @param maxOrderNotional 单笔委托最大金额（分）
 * @param marketMaxPrice  市价买入的保护价：按该价冻结资金，成交价不会高于此价
 */
public record InstrumentSpec(
        String symbol,
        String name,
        long lotSize,
        long tickSize,
        long lowerLimitPrice,
        long upperLimitPrice,
        long maxOrderQty,
        long maxOrderNotional,
        long marketMaxPrice
) {
    public boolean isPriceOnTick(long price) {
        return tickSize > 0 && price % tickSize == 0;
    }
}
