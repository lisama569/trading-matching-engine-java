package com.github.lisama569.tradingmatchingenginejava.model;

/**
 * 下单 / 撤单被拒绝或订单终结时使用的原因码。
 * <p>风控拒绝与撮合终结结果共用这一套编码，但语义不同：
 * <ul>
 *     <li>{@code ACCOUNT_NOT_FOUND} 等风控类原因：订单根本不会创建，接口返回 4xx；</li>
 *     <li>{@code NO_LIQUIDITY}：市价单(IOC)按规则正常终结，订单已创建，状态为 CANCELED。</li>
 * </ul>
 */
public enum RejectCode {

    /** 账户不存在 */
    ACCOUNT_NOT_FOUND,
    /** 标的不存在或未上市 */
    INSTRUMENT_NOT_FOUND,
    /** 买卖方向非法 */
    INVALID_SIDE,
    /** 缺少必填字段 / 请求体无法解析 */
    VALIDATION_FAILED,
    /** 订单类型非法 */
    INVALID_ORDER_TYPE,
    /** 限价单价格非法（为空或 <= 0） */
    INVALID_PRICE,
    /** 委托数量非法（<= 0、非整数或不是最小交易单位的整数倍） */
    INVALID_QTY,
    /** 委托价格超出涨跌停 / 价格带限制 */
    PRICE_OUT_OF_BAND,
    /** 单笔委托数量超过限额 */
    QTY_EXCEEDS_LIMIT,
    /** 单笔委托金额超过限额 */
    NOTIONAL_EXCEEDS_LIMIT,
    /** 买入冻结资金不足 */
    INSUFFICIENT_CASH,
    /** 卖出冻结持仓不足 */
    INSUFFICIENT_POSITION,
    /** 撤单时订单不存在 */
    ORDER_NOT_FOUND,
    /** 撤单时订单已终结（已成交 / 已撤销 / 已拒绝） */
    ORDER_NOT_ACTIVE,
    /** 市价单没有任何对手盘（IOC 终结，非风控拒绝） */
    NO_LIQUIDITY,
    /** 兜底内部错误（不向外部暴露堆栈） */
    SYSTEM_ERROR
}
