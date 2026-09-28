# 本地撮合与下单风控服务（内存状态）

一个纯内存、可并发、结果确定可解释的交易撮合服务。用 Spring Boot 暴露 REST 接口，
内部用「按标的串行撮合 + 按账户加锁结算」模拟订单、资金与持仓，保证并发下单/撤单
与异常输入下撮合结论一致、账实相符。

- 价格单位：**分**（整数）；数量单位：**股**（整数），全程不使用浮点数。
- 初始演示账户（启动自动注入）：`A`（现金 10,000,000 分、AAPL 1,000 股）、
  `B`（现金 2,000,000 分、AAPL 10,000 股等）、`C`（现金 5,000 分、AAPL 100 股）。
- 默认标的：`AAPL`、`MSFT`、`TSLA`。

---

## 1. 撮合规则

### 1.1 撮合优先级（确定、可复现）

1. **价格优先**：买单与卖盘最低价成交，卖单与买盘最高价成交；
2. **同价时间优先**：同一价位按入队序号（sequence）FIFO。入队序号在标的的串行
   撮合线程内单调递增，因此同价时先后顺序确定，不依赖系统时钟精度；
3. **成交价 = 挂单方（maker）价格**，taker 的限价只决定能不能吃、不决定成交价。

订单簿用 `TreeMap<价格, FIFO队列>` 组织，另维护 `orderId -> Order` 哈希索引：

- 取最优价 `O(log n)`；入队 `O(log n)`；
- **撤单 `O(1)`**：哈希索引定位并标记 `CANCELED`，队列项在下次撮合扫到队首时惰性清理，
  不做 `O(n)` 中间摘除；
- 撮合耗时只与实际成交/清理的数量有关，与簿内挂单总量无关。

### 1.2 订单类型与生命周期

| 类型 | 下单冻结 | 不能立即成交时 |
|---|---|---|
| 限价单 BUY | 按 `价格×数量` 全额冻结资金 | 以限价挂入买盘等待 |
| 限价单 SELL | 全额冻结持仓 | 以限价挂入卖盘等待 |
| 市价单 BUY | **不预冻**（价格未知），逐笔按可用资金校验 | 见 1.3 边界定义 |
| 市价单 SELL | 全额冻结持仓 | 见 1.3 边界定义 |

订单状态：`NEW`（未成交在簿）→ `PARTIALLY_FILLED`（部分成交在簿）→
`FILLED`（全部成交）/ `CANCELED`（撤销或市价余量作废）/ `REJECTED`（下单即拒）。

### 1.3 市价单边界的明确定义

- **无对手盘**：下单即拒绝，原因 `MARKET_NO_LIQUIDITY`，不产生任何冻结/成交；
- **有对手盘但资金不足 1 股**（市价买单，最优卖价 > 可用现金）：拒绝，
  原因 `INSUFFICIENT_CASH`；
- **吃穿对手盘后仍有余量**：已成交部分正常结算，**未成交余量一律作废**，
  订单最终状态为 `CANCELED`（`filledQty` 反映已成交部分）；
- **市价买单资金只够买部分数量**：按 `可用现金 / 当前对手价` 取整股数截短成交，
  余量作废，绝不出现「成交了但钱不够扣」；
- **市价卖单数量超过可用持仓**：下单冻结阶段即拒绝 `INSUFFICIENT_POSITION`。

### 1.4 成交结算（一次成交、双边一次记完）

每笔成交在同一个临界区内同时完成双方更新，杜绝「成交了没记账 / 记两次」：

- 买方：按挂单价预算解冻（限价单），按实际成交价扣现金，持仓增加；
  成交价低于挂单价时，差价自动退回可用资金（价格改善）；
- 卖方：从卖出冻结中扣减持仓，卖出所得现金入账；
- 同步生成一条不可变的 `Trade` 记录，成交记录数量之和恒等于订单总成交量。

> 说明：本服务允许同一账户自有买卖单互撮（模拟环境简化处理），结算逻辑与跨账户完全一致。

---

## 2. 风控校验与拒绝原因

校验分两层，拒绝都返回稳定的原因码（HTTP 200 + `success=false` + `rejectReason`），
`message` 给出可解释细节。服务层只做无状态静态校验，账户/标的/资金等状态校验在引擎内完成。

| rejectReason | 触发条件 |
|---|---|
| `INVALID_ACCOUNT` | accountId 为空或账户不存在 |
| `INVALID_SYMBOL` | symbol 为空或未注册标的 |
| `INVALID_SIDE` | side 不是 BUY/SELL |
| `INVALID_ORDER_TYPE` | type 不是 LIMIT/MARKET |
| `INVALID_PRICE` | 限价单缺 price，或 price ≤ 0（市价单忽略 price） |
| `INVALID_QUANTITY` | quantity 为空或 ≤ 0 |
| `EXCEEDS_LIMIT` | 单笔数量超 `trading.limits.max-order-quantity`（默认 100 万股），或名义金额（价格×数量）超 `trading.limits.max-order-notional`（默认 10 亿 元=10^10 分），或乘法溢出 |
| `INSUFFICIENT_CASH` | 买单可用资金不足（含冻结占用后、市价连 1 股都付不起） |
| `INSUFFICIENT_POSITION` | 卖单可用持仓不足（含冻结占用后） |
| `ORDER_NOT_FOUND` | 撤单/查询的订单不存在；撤他人订单也统一报此原因（不泄露订单存在性） |
| `ORDER_ALREADY_FINISHED` | 撤销已成交/已撤销的订单（并发撤单只有一个成功，其余落此原因） |
| `MARKET_NO_LIQUIDITY` | 市价单无对手盘 |
| `SYSTEM_ERROR` | 系统繁忙/内部错误；内部堆栈只进日志，**绝不透传到接口** |

校验顺序固定（先字段、后枚举、再数量/价格、最后限额，冻结阶段再查资金/持仓），
同一非法输入永远得到同一结论。

---

## 3. 并发一致性保证

1. **同一标的串行撮合**：每个 symbol 一个单线程执行器（`matching-<symbol>`），
   撮合、入簿、撤单标记、序号分配都在该线程内串行完成 → 订单状态与订单簿无竞争，
   时间优先级确定，不会重复成交/超量成交/把订单弄丢。
2. **账户资金/持仓加锁**：每次修改都持有该账户的 `ReentrantLock`；
   一笔成交涉及两个账户时，**始终按 accountId 升序加锁**（相同则只加一次），
   从根本上消除环路死锁。
3. **冻结先于撮合**：限价单/市价卖单在进入撮合队列前完成冻结（只持单账户锁），
   账户领域对象对每个操作都有不变量保护（现金、冻结、持仓、冻结持仓均非负，
   冻结不得超过总额），一旦可能破坏立即抛内部错误而非静默放行 ——
   因而并发下冻结资金/持仓不可能被扣成负数。
4. **撤单与撮合竞争**：两者都在 symbol 串行线程内排队，串行线程内对状态二次确认；
   已全部成交的订单撤单返回 `ORDER_ALREADY_FINISHED`，已冻结部分随撤单足额释放。
5. **异常收敛**：撮合线程内部未预期异常被 `ExecutionException` 捕获并转成
   `SYSTEM_ERROR` 响应；拒绝路径会回滚已冻结的市价卖单持仓，不留脏冻结。

对账恒等式（测试中逐项断言）：

- 全市场现金总额守恒（买方扣款 = 卖方收款）；
- 全市场某标的持仓总量守恒（买方增 = 卖方减）；
- 任一账户：`现金 ≥ 冻结现金 ≥ 0`、`持仓 ≥ 冻结持仓 ≥ 0`；
- `Trade` 数量之和 = 各订单成交量之和 = 账户资金/持仓变动量。

---

## 4. HTTP 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/orders` | 下单，body 见下 |
| POST | `/api/orders/cancel` | 撤单 `{accountId, orderId}` |
| GET | `/api/orders/{orderId}` | 查订单 |
| GET | `/api/accounts/{accountId}` | 账户资金/持仓快照（不存在返回 404） |

下单请求：

```json
{ "accountId": "A", "symbol": "AAPL", "side": "BUY",
  "type": "LIMIT", "price": 15000, "quantity": 100 }
```

成功响应（业务失败也是 HTTP 200，靠 success/rejectReason 区分）：

```json
{ "success": true,
  "order": { "id": 1000001, "status": "FILLED", "quantity": 100,
             "filledQty": 100, "avgFillPrice": 15000, ... } }
```

---

## 5. 本地验证方法

要求 JDK 21+、Maven 3.9+（仓库已带 Spring Boot 依赖，支持离线构建）。

```bash
# 1) 全量测试（24 个用例：撮合顺序/流程/拒绝原因/并发/性能/Web）
mvn test
mvn test -o                      # 离线

# 只跑某一类
mvn test -Dtest=OrderBookTest                 # 价格/时间优先、部分成交、惰性撤单
mvn test -Dtest=MatchingEngineFlowTest        # 成交/撤单/市价边界 + 资金持仓对账
mvn test -Dtest=MatchingConcurrencyTest       # 并发下单/撤单一致性 + 守恒对账
mvn test -Dtest=MatchingPerformanceTest       # 10 万挂单性能基准
mvn test -Dtest=RiskRejectionTest             # 各类非法输入拒绝原因可区分
mvn test -Dtest=TradingControllerTest         # HTTP 全链路冒烟

# 2) 启动服务（application.properties 中 server.port=0 为随机端口，可改成固定端口）
mvn spring-boot:run

# 3) 手动调用示例
curl -s -XPOST localhost:8080/api/orders -H 'Content-Type: application/json' \
  -d '{"accountId":"B","symbol":"AAPL","side":"SELL","type":"LIMIT","price":15000,"quantity":100}'
curl -s -XPOST localhost:8080/api/orders -H 'Content-Type: application/json' \
  -d '{"accountId":"A","symbol":"AAPL","side":"BUY","type":"LIMIT","price":15000,"quantity":40}'
curl -s localhost:8080/api/accounts/B
```

单测日志会打印每笔订单的**输入字段**与**判定依据**（如「冻结资金 X、可用 Y」
「按可用资金截短为 N 股」「同价 FIFO 命中 makerId」「拒绝原因=…」），
便于复盘每一次撮合结论。

### 关键配置（`application.properties`，均有默认值）

```properties
trading.symbols=AAPL,MSFT,TSLA
trading.limits.max-order-quantity=1000000
trading.limits.max-order-notional=10000000000
```

### 性能基准结果（参考，本机）

- 10 万笔限价挂单：下单平均约 20 µs/笔；撤单平均约 10 µs/笔（O(1) 哈希索引）；
- 簿深从 1 万扩大到 10 万时，单笔市价吃最优价耗时无明显增长（亚毫秒级）。
