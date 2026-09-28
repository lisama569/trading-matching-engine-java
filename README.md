# 本地撮合与下单风控服务（内存版）

一套基于 Spring Boot 的**单机内存**交易撮合 + 下单风控服务。订单、资金、持仓全部保存在内存中，
重启即重置；不依赖任何外部数据库或消息中间件。目标是在**并发下单/撤单**与**异常输入**下，
让撮合结果始终确定、可解释，并且账实严格自洽。

---

## 1. 数据模型与金额约定

| 概念 | 说明 |
|---|---|
| 价格 | **整数**，单位为最小货币单位（分）。例如 10.05 元传 `1005`，杜绝浮点误差。 |
| 数量 | 整数（股/份），必须是合约最小交易单位 `lotSize` 的整数倍。 |
| 账户 | `cash`（现金总额）、`frozenCash`（买入冻结），可用 = `cash - frozenCash`。 |
| 持仓 | `qty`（净持仓）、`frozenQty`（卖出冻结），可用 = `qty - frozenQty`。 |
| 订单 | `NEW / PARTIALLY_FILLED / FILLED / CANCELED / REJECTED`。 |

启动时自动预置（见 `BootstrapDataInitializer`）：

- 标的：`BTC`（tick=5，价格带 10000~20000 分，市价保护价 20000）、`ETH`（lot=10，tick=1，价格带 1000~5000）；
- 账户：`1=alice`（现金 1,000,000）、`2=bob`（现金 500,000 + BTC 100 + ETH 500）、`3=carol`（现金 100,000）。

---

## 2. 撮合规则

1. **订单类型**
   - `LIMIT`：限价单。先与对手盘撮合，未成交部分**挂入撮合簿**（GTC）。
   - `MARKET`：市价单，按 **IOC（Immediate-Or-Cancel）** 处理：只与当前对手盘立即撮合，
     未成交部分立即终结为 `CANCELED`（`rejectReason=NO_LIQUIDITY`），**绝不挂单**。
     - 无任何对手盘、一股未成交：仍是 `CANCELED/NO_LIQUIDITY`（HTTP 200，这是规则内的正常终结，不是风控拒绝）。
     - 市价买入按合约保护价 `marketMaxPrice` 冻结资金，防止买到异常高价；多冻结的差额在终结时释放。
2. **撮合优先级（确定且可复现）**
   - **价格优先**：买盘取最高价、卖盘取最低价（`TreeMap` 维护有序价位）。
   - **同价时间优先**：同一价位 FIFO 队列；订单 id 与全局序列号在同一原子计数器上单调递增，
     先入簿者序列号一定更小，因此先后顺序没有歧义。
   - **成交价**：取被动挂单方（maker）的限价。taker 限价只用于决定"能不能吃到这个价位"。
   - **成交量**：`min(taker 剩余量, maker 剩余量)`，支持双方部分成交。
3. **成交交割（撮合与记账在同一临界区，一次完成）**
   - 买方：冻结资金 → 支付（`cash` 与 `frozenCash` 同时减少成交额），持仓增加；
   - 卖方：冻结持仓 → 扣减（`qty` 与 `frozenQty` 同时减少成交量），现金增加。
   - 因此恒成立：成交即记账、只记一次；冻结额不会被扣成负数。
4. **撤单**：限价挂单从簿中移除（即时删除 + 惰性清理双保险），剩余冻结一次性释放。
   撤单是幂等判定：已终结订单再撤返回 `ORDER_NOT_ACTIVE`，不会重复释放冻结。

---

## 3. 风控校验与拒绝原因

下单校验顺序（前一步不过就不会执行后面的步骤，拒绝原因因此唯一、可区分）：

| 顺序 | 检查 | RejectCode | HTTP |
|---|---|---|---|
| 1 | 账户是否存在 | `ACCOUNT_NOT_FOUND` | 404 |
| 2 | 标的是否存在 | `INSTRUMENT_NOT_FOUND` | 404 |
| 3 | 方向是否为 BUY/SELL | `INVALID_SIDE` | 400 |
| 4 | 类型是否为 LIMIT/MARKET | `INVALID_ORDER_TYPE` | 400 |
| 5 | 数量为正整数、为 lotSize 整数倍 | `INVALID_QTY` | 400 |
| 6 | 数量 ≤ 单笔最大量 | `QTY_EXCEEDS_LIMIT` | 422 |
| 7 | 限价：非空、>0、符合 tick、在价格带内 | `INVALID_PRICE` / `PRICE_OUT_OF_BAND` | 400 / 422 |
| 8 | 买入金额 ≤ 单笔最大金额 | `NOTIONAL_EXCEEDS_LIMIT` | 422 |
| 9 | 买入：可用资金足够冻结 | `INSUFFICIENT_CASH` | 422 |
| 10 | 卖出：可用持仓足够冻结 | `INSUFFICIENT_POSITION` | 422 |
| — | 请求缺字段 / JSON 非法 | `VALIDATION_FAILED` | 400 |
| — | 撤单找不到（含撤别人的单） | `ORDER_NOT_FOUND` | 404 |
| — | 撤已 FILLED/CANCELED/REJECTED 的单 | `ORDER_NOT_ACTIVE` | 409 |
| — | 市价单无对手盘的 IOC 终结 | `NO_LIQUIDITY`（订单状态内，HTTP 200） | 200 |
| — | 未预期内部异常 | `SYSTEM_ERROR`（堆栈只进服务端日志） | 500 |

**冻结策略**：买入（限价按限价、市价按保护价）冻结 `price*qty` 资金；卖出冻结 `qty` 持仓。
冻结发生在撮合之前；冻结失败订单不会创建、不留盘口、不改变任何余额。

---

## 4. 并发一致性保证

采用**两级锁**，锁的获取方向只有一种，因此数学上不可能形成等待环（无死锁）：

```
风控冻结阶段：  线程 ──> 账户锁（synchronized Account）          # 冻结完立即释放
撮合/撤单阶段：线程 ──> 标的锁（synchronized per-symbol）
                          └──> 账户锁（按账户 id 升序获取）        # 成交交割时
```

要点：

1. **同一账户串行、不同账户并行**：同一账户的"检查 + 冻结"在账户锁内一次完成（check-then-act 原子化），
   并发下单不可能把可用资金/持仓扣成负数；不同账户之间互不阻塞。
2. **同一标的串行撮合、不同标的并行**：撮合簿的所有读写、订单状态推进、成交交割、撤单
   都在标的锁内完成。一笔成交要么完全不发生，要么撮合同步完成双方记账，不存在中间态。
3. **成交交割时**同时改买卖两个账户，按 `accountId` 升序获取账户锁，锁顺序全局一致。
4. **冻结只释放一次**：订单终结（成交完毕 / 撤单 / IOC）是唯一开关，
   且终结判定与冻结释放都在标的锁内；并发撤单恰好 1 次成功，其余得到 `ORDER_NOT_ACTIVE`。
5. **注册先于撮合**：订单先放入全局表再进入撮合，因此"撮合未完成的订单"对撤单请求一定可见，订单不会丢。
6. **性能**：价位用 `TreeMap`（O(log 价位数)），价位内 FIFO 队列 O(1)，
   单笔撮合复杂度只与价位数有关、与历史挂单总量无关（有测试覆盖：20 万挂单规模对比）。

---

## 5. HTTP 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/orders` | 下单，body 见下 |
| POST | `/api/orders/cancel` | 撤单 `{accountId, orderId}`（只能撤本人的单） |
| GET  | `/api/orders/{orderId}` | 查询订单状态/成交均价 |
| GET  | `/api/accounts/{accountId}` | 现金、冻结、各持仓（锁内快照，自洽） |
| GET  | `/api/books/{symbol}?depth=n` | 盘口买卖档位 `[price,totalQty,activeQty]` |
| GET  | `/api/trades/{symbol}?limit=n` | 最近成交流水 |

下单请求示例：

```bash
curl -s -X POST localhost:8080/api/orders -H 'Content-Type: application/json' \
  -d '{"accountId":1,"symbol":"BTC","side":"BUY","type":"LIMIT","price":15000,"qty":5}'
```

成功响应（市价无对手盘时也是 200，靠 `status`/`rejectReason` 区分）：

```json
{
  "accepted": true, "orderId": 42, "symbol": "BTC", "side": "BUY", "type": "LIMIT",
  "price": 15000, "origQty": 5, "filledQty": 5, "remainingQty": 0,
  "avgFillPrice": 15000, "status": "FILLED", "rejectReason": null,
  "trades": [{"tradeId": 7, "price": 15000, "qty": 5, "counterpartyOrderId": 41, "timestampMillis": 1780000000000}]
}
```

拒绝响应（结构化、无堆栈）：

```json
{"code": "INSUFFICIENT_CASH", "message": "可用资金不足: need=200000, available=100000"}
```

---

## 6. 本地验证方法

前置：JDK 21+、Maven 3.9+。

```bash
# 1) 编译
mvn compile

# 2) 跑全部测试（24 个：撮合规则 / 风控拒绝 / 并发一致性 / REST 端到端 / 性能）
mvn test

# 3) 只跑某一组
mvn test -Dtest=MatchingEngineTest       # 撮合：全成/部分/价格时间优先/不穿越/IOC/撤单
mvn test -Dtest=RiskControlTest          # 每种非法输入与不足都断言到具体 RejectCode
mvn test -Dtest=ConcurrencyTest          # 64 并发买、50 账户抢吃、20 线程撤单、200 轮撤单/成交竞争
mvn test -Dtest=TradingApiIntegrationTest#fullTradeFlowOverHttp
mvn test -Dtest=MatchingPerformanceTest  # 1k vs 200k 挂单规模的撮合耗时对比

# 4) 启动服务手工验证（application.properties 已固定 server.port=8080）
mvn spring-boot:run
```

### 手工冒烟脚本

```bash
# bob 挂 5 BTC @15000（卖）
curl -s -X POST localhost:8080/api/orders -H 'Content-Type: application/json' \
  -d '{"accountId":2,"symbol":"BTC","side":"SELL","type":"LIMIT","price":15000,"qty":5}'
# alice 限价买入 5，立即全部成交
curl -s -X POST localhost:8080/api/orders -H 'Content-Type: application/json' \
  -d '{"accountId":1,"symbol":"BTC","side":"BUY","type":"LIMIT","price":15000,"qty":5}'
# 核对账户（现金应减少 75000，BTC +5；bob 反之）
curl -s localhost:8080/api/accounts/1
curl -s localhost:8080/api/accounts/2
# 市价单无对手盘 → CANCELED/NO_LIQUIDITY，且冻结资金归零
curl -s -X POST localhost:8080/api/orders -H 'Content-Type: application/json' \
  -d '{"accountId":3,"symbol":"ETH","side":"BUY","type":"MARKET","qty":10}'
```

单测日志中每个用例都会打印 **`[用例输入]`**（订单输入）与 **`[判定依据]`**（为什么接受/拒绝/成交），
成交与冻结动作也有 INFO 日志，可直接对照复盘。

### 测试如何证明"账实自洽"

- 每笔成交后断言：买卖双方现金变化 = 成交金额，持仓变化 = 成交量，冻结清零/继续冻结的剩余量正确；
- 并发场景额外断言：**系统内总现金、总持仓跨所有成交守恒**；总成交量恰好等于挂单量（不超量、不丢量）；
  所有并发请求都有确定结论（接受数 + 拒绝数 = 请求数，不丢单）。

---

## 7. 主要代码结构

```
model/        Side / OrderType / OrderStatus / RejectCode
              Order / Trade / Account / Position / InstrumentSpec
common/       TradingException（携带 RejectCode）
engine/       OrderBook（TreeMap 价位 + FIFO 队列）、MatchingEngine（标的锁/撮合/交割/撤单）
service/      OrderService（风控 + 冻结编排）、AccountService（账户锁入口）、
              MarketDataService（盘口/成交流水）、BootstrapDataInitializer（预置内存数据）
web/          TradingController、GlobalExceptionHandler、dto/*
```
