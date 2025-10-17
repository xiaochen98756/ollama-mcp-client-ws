package com.client.ws.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Mock 组委会提供的 HTTP 工具 API
 * 实际比赛时，替换为组委会提供的真实 API 地址即可
 */
@Slf4j
@RestController
@RequestMapping("/mock-tool") // Mock 工具 API 前缀
public class MockToolController {

    /**
     * Mock 汇率换算工具 API
     * 请求参数：{"amount": 100, "fromCurrency": "USD", "toCurrency": "CNY"}
     * 响应结果：{"result": 715.32, "fromCurrency": "USD", "toCurrency": "CNY"}
     */
    @PostMapping("/exchange-rate")
    public Map<String, Object> mockExchangeRate(@RequestBody Map<String, Object> params) {
        // 1. 解析请求参数（模拟组委会 API 的参数格式）
        BigDecimal amount = new BigDecimal(params.get("amount").toString());
        String fromCurrency = params.get("fromCurrency").toString();
        String toCurrency = params.get("toCurrency").toString();
        log.info("收到汇率换算请求：金额={}，源币种={}，目标币种={}", amount, fromCurrency, toCurrency);

        // 2. Mock 计算逻辑（实际由组委会 API 实现）
        BigDecimal rate = new BigDecimal("7.1532"); // 假设 1 USD = 7.1532 CNY
        BigDecimal result = amount.multiply(rate).setScale(2, BigDecimal.ROUND_HALF_UP);

        // 3. 返回 Mock 结果（匹配组委会 API 的响应格式）
        return Map.of(
                "code", 200,
                "message", "success",
                "data", Map.of(
                        "result", result,
                        "fromCurrency", fromCurrency,
                        "toCurrency", toCurrency,
                        "rate", rate
                )
        );
    }

    /**
     * Mock 手续费计算工具 API
     * 请求参数：{"tradeAmount": 500, "merchantType": "ONLINE", "tradeType": "PAY"}
     * 响应结果：{"serviceFee": 2.5, "tradeAmount": 500}
     */
    @PostMapping("/service-fee")
    public Map<String, Object> mockServiceFee(@RequestBody Map<String, Object> params) {
        // 1. 解析请求参数
        BigDecimal tradeAmount = new BigDecimal(params.get("tradeAmount").toString());
        String merchantType = params.get("merchantType").toString();
        log.info("收到手续费计算请求：交易金额={}，商户类型={}", tradeAmount, merchantType);

        // 2. Mock 计算逻辑（如 ONLINE 商户手续费率 0.5%）
        BigDecimal rate = new BigDecimal("0.005");
        BigDecimal serviceFee = tradeAmount.multiply(rate).setScale(2, BigDecimal.ROUND_HALF_UP);

        // 3. 返回 Mock 结果
        return Map.of(
                "code", 200,
                "message", "success",
                "data", Map.of(
                        "serviceFee", serviceFee,
                        "tradeAmount", tradeAmount,
                        "merchantType", merchantType
                )
        );
    }
}