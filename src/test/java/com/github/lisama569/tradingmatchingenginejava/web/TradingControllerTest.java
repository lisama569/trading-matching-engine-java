package com.github.lisama569.tradingmatchingenginejava.web;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web 层冒烟：走完整 Spring 链路，验证响应结构、拒绝原因与异常收敛。
 * 演示数据由 SeedDataInitializer 注入（A/B/C 账户与 AAPL 持仓）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TradingControllerTest {

    private static final Logger log = LoggerFactory.getLogger(TradingControllerTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void placeInvalidOrderReturnsStructuredReason() throws Exception {
        log.info("[HTTP输入] 下单数量为0，期望结构化拒绝 INVALID_QUANTITY");
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"A","symbol":"AAPL","side":"BUY","type":"LIMIT","price":100,"quantity":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.rejectReason").value("INVALID_QUANTITY"));
        log.info("[HTTP判定] 业务拒绝 HTTP=200 且 reason 可区分");
    }

    @Test
    void fullLifecycleOverHttp_orderFillCancelAndAccount() throws Exception {
        // B 卖 100 股 AAPL @15000 限价挂单。
        log.info("[HTTP输入] B 卖出 AAPL 100@15000 限价单");
        String askId = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"B","symbol":"AAPL","side":"SELL","type":"LIMIT","price":15000,"quantity":100}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.order.status").value("NEW"))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"id\":(\\d+).*", "$1");
        log.info("[HTTP判定] 卖单挂簿成功 orderId={}", askId);

        // A 买 40 股全部成交，订单 FILLED。
        log.info("[HTTP输入] A 买入 AAPL 40@15000，应与B部分成交");
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"A","symbol":"AAPL","side":"BUY","type":"LIMIT","price":15000,"quantity":40}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("FILLED"))
                .andExpect(jsonPath("$.order.filledQty").value(40));

        // B 撤销剩余 60 股。
        log.info("[HTTP输入] B 撤单 orderId={}", askId);
        mockMvc.perform(post("/api/orders/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"B\",\"orderId\":" + askId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // B 账户：AAPL 初始10000，卖40剩9960，冻结归零。
        mockMvc.perform(get("/api/accounts/B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positions.AAPL[0]").value(9960))
                .andExpect(jsonPath("$.positions.AAPL[1]").value(0));
        log.info("[HTTP结论] 成交+撤单后 B 持仓9960、冻结0，账实相符");
    }

    @Test
    void unknownAccount404AndMalformedBodyDoesNotLeak() throws Exception {
        mockMvc.perform(get("/api/accounts/NOBODY"))
                .andExpect(status().isNotFound());
        log.info("[HTTP判定] 未知账户查询返回404");

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.rejectReason").value("SYSTEM_ERROR"));
        log.info("[HTTP判定] 畸形请求体仅返回 SYSTEM_ERROR，无堆栈外泄");
    }
}
