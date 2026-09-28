package com.github.lisama569.tradingmatchingenginejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 端到端测试（启动完整 Spring 上下文 + MockMvc）。
 * 覆盖：下单成交、市价 IOC 终结、各类风控拒绝码、非法 JSON 不透传、撤单冲突、账户自洽查询。
 */
@SpringBootTest
class TradingApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(TradingApiIntegrationTest.class);
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mvc;

    private MockMvc mvc() {
        if (mvc == null) {
            mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        }
        return mvc;
    }

    private MvcResult placeOrder(String body) throws Exception {
        log.info("[HTTP 请求] POST /api/orders body={}", body);
        MvcResult result = mvc().perform(post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        log.info("[HTTP 响应] status={}, body={}", result.getResponse().getStatus(),
                result.getResponse().getContentAsString());
        return result;
    }

    @Test
    @DisplayName("完整流程：卖方挂单 -> 买方成交 -> 账户账实相符 -> 盘口清空")
    void fullTradeFlowOverHttp() throws Exception {
        // bob(2) 初始持有 BTC 100；alice(1) 现金 1,000,000
        MvcResult sell = placeOrder("""
                {"accountId":2,"symbol":"BTC","side":"SELL","type":"LIMIT","price":15000,"qty":5}
                """);
        org.junit.jupiter.api.Assertions.assertEquals(200, sell.getResponse().getStatus());
        long sellOrderId = json.readTree(sell.getResponse().getContentAsString()).get("orderId").asLong();

        MvcResult buy = placeOrder("""
                {"accountId":1,"symbol":"BTC","side":"BUY","type":"LIMIT","price":15000,"qty":5}
                """);
        org.junit.jupiter.api.Assertions.assertEquals(200, buy.getResponse().getStatus());
        JsonNode buyResp = json.readTree(buy.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertEquals("FILLED", buyResp.get("status").asText());
        org.junit.jupiter.api.Assertions.assertEquals(1, buyResp.get("trades").size());
        org.junit.jupiter.api.Assertions.assertEquals(15000, buyResp.get("trades").get(0).get("price").asLong());

        // 卖方订单查询也应为 FILLED
        mvc().perform(get("/api/orders/" + sellOrderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILLED"));

        // 账户自洽：alice 现金减少 75000，多 5 BTC；bob 现金增加 75000，BTC 减 5
        mvc().perform(get("/api/accounts/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cash").value(1_000_000 - 75_000))
                .andExpect(jsonPath("$.frozenCash").value(0))
                .andExpect(jsonPath("$.availableCash").value(1_000_000 - 75_000));
        mvc().perform(get("/api/accounts/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cash").value(500_000 + 75_000));

        // 盘口卖一应消失
        mvc().perform(get("/api/books/BTC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asks").isArray())
                .andExpect(jsonPath("$.asks[0]").doesNotExist());
        log.info("[判定依据] 成交后双方账户、订单状态、盘口全部自洽");
    }

    @Test
    @DisplayName("市价单无对手盘：HTTP 200 + CANCELED + NO_LIQUIDITY（与风控拒绝区分）")
    void marketOrderNoLiquidityOverHttp() throws Exception {
        // carol(3) 买入一个当前没有卖盘的标的的价格带顶端之外场景；这里直接用新标的 ETH，初始无挂单
        MvcResult result = placeOrder("""
                {"accountId":3,"symbol":"ETH","side":"BUY","type":"MARKET","qty":10}
                """);
        org.junit.jupiter.api.Assertions.assertEquals(200, result.getResponse().getStatus());
        JsonNode node = json.readTree(result.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertEquals("CANCELED", node.get("status").asText());
        org.junit.jupiter.api.Assertions.assertEquals("NO_LIQUIDITY", node.get("rejectReason").asText());
        org.junit.jupiter.api.Assertions.assertEquals(0, node.get("filledQty").asLong());
        // 保护价冻结的资金应已释放
        mvc().perform(get("/api/accounts/3"))
                .andExpect(jsonPath("$.frozenCash").value(0));
        log.info("[判定依据] 无对手盘的市价单按 IOC 正常终结，不占用资金");
    }

    @Test
    @DisplayName("风控拒绝全部映射到稳定错误码与正确的 HTTP 状态")
    void riskRejectionsOverHttp() throws Exception {
        assertError(400, "VALIDATION_FAILED", """
                {"accountId":1,"symbol":"BTC","side":"BUY","type":"LIMIT","price":15000}""",
                "缺少 qty");
        assertError(400, "VALIDATION_FAILED", "{not-json", "非法 JSON 体");
        assertError(404, "ACCOUNT_NOT_FOUND", """
                {"accountId":999,"symbol":"BTC","side":"BUY","type":"LIMIT","price":15000,"qty":1}""",
                "账户不存在");
        assertError(404, "INSTRUMENT_NOT_FOUND", """
                {"accountId":1,"symbol":"DOGE","side":"BUY","type":"LIMIT","price":15000,"qty":1}""",
                "标的不存在");
        assertError(400, "INVALID_SIDE", """
                {"accountId":1,"symbol":"BTC","side":"HOLD","type":"LIMIT","price":15000,"qty":1}""",
                "非法方向");
        assertError(400, "INVALID_PRICE", """
                {"accountId":1,"symbol":"BTC","side":"BUY","type":"LIMIT","price":-1,"qty":1}""",
                "非法价格");
        assertError(422, "PRICE_OUT_OF_BAND", """
                {"accountId":1,"symbol":"BTC","side":"BUY","type":"LIMIT","price":999995,"qty":1}""",
                "价格超带");
        assertError(422, "INSUFFICIENT_CASH", """
                {"accountId":3,"symbol":"BTC","side":"BUY","type":"LIMIT","price":20000,"qty":1000}""",
                "carol 资金不足");
        // carol 没有 BTC，卖出必拒
        assertError(422, "INSUFFICIENT_POSITION", """
                {"accountId":3,"symbol":"BTC","side":"SELL","type":"LIMIT","price":15000,"qty":1}""",
                "carol 持仓不足");
    }

    @Test
    @DisplayName("撤单冲突：不存在 404，已终结 409，且错误响应不含堆栈")
    void cancelErrorsOverHttp() throws Exception {
        mvc().perform(post("/api/orders/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":1,\"orderId\":888888}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));

        // 挂单再撤两次
        MvcResult placed = placeOrder("""
                {"accountId":1,"symbol":"BTC","side":"BUY","type":"LIMIT","price":10100,"qty":1}
                """);
        long orderId = json.readTree(placed.getResponse().getContentAsString()).get("orderId").asLong();
        mvc().perform(post("/api/orders/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":1,\"orderId\":" + orderId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
        MvcResult second = mvc().perform(post("/api/orders/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":1,\"orderId\":" + orderId + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_ACTIVE"))
                .andReturn();
        String body = second.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("at com.github"), "错误响应不得包含堆栈信息");
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("Exception"), "错误响应不得暴露异常类型名");
        log.info("[判定依据] 二次撤单返回 409/ORDER_NOT_ACTIVE，且响应体干净");
    }

    private void assertError(int httpStatus, String code, String body, String desc) throws Exception {
        log.info("[用例输入] {} -> 期望 {} / {}", desc, httpStatus, code);
        MvcResult result = placeOrder(body);
        org.junit.jupiter.api.Assertions.assertEquals(httpStatus, result.getResponse().getStatus(),
                desc + " 实际响应: " + result.getResponse().getContentAsString());
        JsonNode node = json.readTree(result.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertEquals(code, node.get("code").asText());
        org.junit.jupiter.api.Assertions.assertTrue(node.get("message").asText().length() > 0,
                "拒绝必须给出可解释的原因文案");
        log.info("[判定依据] 命中 code={} message={}", code, node.get("message").asText());
    }
}
