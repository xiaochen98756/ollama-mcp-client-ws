package com.client.mingyuming.controller;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 完全对齐组委会 API 格式的 Mock 服务
 * 1. 支持 GET 请求 + URL 参数
 * 2. 校验鉴权头（X-App-Id/X-App-Key）
 * 3. 响应字段与文档完全一致（含脱敏、时间格式）
 */
@Slf4j
@RestController
@RequestMapping("/mock") // 与组委会 API 前缀一致
public class OrgToolMockController {

    // -------------------------- 通用配置 --------------------------
    // 模拟合法鉴权信息（比赛时替换为组委会分配的真实 AppId/AppKey）
    private static final String VALID_APP_ID = "team_123";
    private static final String VALID_APP_KEY = "key_456abc";
    // 东八区时间格式化器
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

//    // -------------------------- 鉴权拦截（所有接口通用） --------------------------
//    @ModelAttribute
//    public void validateAuth(@RequestHeader("X-App-Id") String appId,
//                             @RequestHeader("X-App-Key") String appKey) {
//        // 模拟鉴权校验（不通过则抛异常）
//        if (!VALID_APP_ID.equals(appId) || !VALID_APP_KEY.equals(appKey)) {
//            throw new RuntimeException("鉴权失败：无效的 X-App-Id 或 X-App-Key");
//        }
//    }

    // -------------------------- 1. 信用卡账单服务 --------------------------
    @GetMapping("/credit-card/monthly-bill")
    public Map<String, Object> creditCardBill(
            @RequestParam("cardNumber") String cardNumber, // 必选：信用卡号
            @RequestParam("month") @DateTimeFormat(pattern = "yyyy-MM") String month) { // 必选：账单月

        log.info("Mock 信用卡账单查询：卡号={}，月份={}", cardNumber, month);

        // 模拟脱敏卡号（前4位+****+后4位）
        String maskedCard = cardNumber.substring(0, 4) + "****" + cardNumber.substring(cardNumber.length() - 4);

        // 构造文档一致的响应
        return Map.of(
                "card_number", maskedCard,
                "cardholder_name", "张三",
                "bank", "中国银行",
                "bill_month", month,
                "total_amount", new BigDecimal("15680.50"),
                "minimum_payment", new BigDecimal("1568.05"),
                "payment_status", "unpaid", // 未支付
                "due_date", LocalDate.of(2025, 10, 15).format(DATE_FORMATTER),
                "currency", "CNY"
        );
    }

    // -------------------------- 2. 汇率服务 --------------------------
    @GetMapping("/exchange-rate")
    public Map<String, Object> exchangeRate(
            @RequestParam("fromCurrency") String fromCurrency, // 必选：源货币
            @RequestParam("toCurrency") String toCurrency,     // 必选：目标货币
            @RequestParam(value = "amount", defaultValue = "1") BigDecimal amount) { // 可选：金额，默认1

        log.info("Mock 汇率查询：源货币={}，目标货币={}，金额={}", fromCurrency, toCurrency, amount);

        // 模拟实时汇率（固定值用于测试，实际由组委会返回）
        BigDecimal rate = new BigDecimal("7.25");
        BigDecimal convertedAmount = amount.multiply(rate).setScale(2, BigDecimal.ROUND_HALF_UP);

        // 构造文档一致的响应
        return Map.of(
                "from_currency", fromCurrency,
                "to_currency", toCurrency,
                "rate", rate,
                "amount", amount,
                "converted_amount", convertedAmount,
                "timestamp", LocalDateTime.now().format(DATETIME_FORMATTER)
        );
    }

    // -------------------------- 3. 水电煤账单服务 --------------------------
    @GetMapping("/utility-bill/monthly-bill")
    public Map<String, Object> utilityBill(
            @RequestParam("householdId") String householdId, // 必选：户号
            @RequestParam("month") @DateTimeFormat(pattern = "yyyy-MM") String month, // 必选：月份
            @RequestParam(value = "utilityType", defaultValue = "electricity") String utilityType) { // 可选：类型，默认电费

        log.info("Mock 水电煤账单查询：户号={}，月份={}，类型={}", householdId, month, utilityType);

        // 按账单类型模拟不同数据
        String usageUnit = switch (utilityType) {
            case "water" -> "m³";
            case "gas" -> "m³";
            default -> "kWh"; // electricity
        };
        BigDecimal usageAmount = switch (utilityType) {
            case "water" -> new BigDecimal("15.8");
            case "gas" -> new BigDecimal("28.5");
            default -> new BigDecimal("286.8"); // electricity
        };
        BigDecimal billAmount = switch (utilityType) {
            case "water" -> new BigDecimal("63.2");
            case "gas" -> new BigDecimal("114.0");
            default -> new BigDecimal("206.8"); // electricity
        };

        // 构造文档一致的响应
        return Map.of(
                "household_id", householdId,
                "customer_name", "张三",
                "address", "北京市朝阳区建国门外大街1号",
                "utility_type", utilityType,
                "bill_month", month,
                "usage_amount", usageAmount,
                "usage_unit", usageUnit,
                "bill_amount", billAmount,
                "payment_status", "unpaid",
                "currency", "CNY"
        );
    }

    // -------------------------- 4. 用户资产服务 --------------------------
    @GetMapping("/user/assets")
    public Map<String, Object> userAssets(
            @RequestParam("customerId") String customerId, // 必选：用户ID（身份证）
            @RequestParam(value = "assetType", defaultValue = "card") String assetType) { // 可选：资产类型，默认信用卡

        log.info("Mock 用户资产查询：用户ID={}，资产类型={}", customerId, assetType);

        // 模拟脱敏用户ID（前4位+****+后4位）
        String maskedCustomerId = customerId.substring(0, 4) + "****" + customerId.substring(customerId.length() - 4);

        // 信用卡资产响应
        List<CardAsset> cards = List.of(
                new CardAsset("4111111111111111", "张三", "中国银行", "Unionpay", new BigDecimal("50000.0")),
                new CardAsset("5555111111112222", "张三", "招商银行", "Visa", new BigDecimal("30000.0"))
        );
        return Map.of(
                "customer_id", maskedCustomerId,
                "cards", cards
        );
    }

    // -------------------------- 5. 支付订单服务 --------------------------
    @GetMapping("/qr/create-payment-order")
    public Map<String, Object> createPaymentOrder(
            @RequestParam("merchantId") String merchantId, // 必选：商户号
            @RequestParam("orderId") String orderId,       // 必选：订单号
            @RequestParam(value = "amount", defaultValue = "0.00") BigDecimal amount) { // 可选：金额

        log.info("Mock 支付订单创建：商户号={}，订单号={}，金额={}", merchantId, orderId, amount);

        // 模拟系统生成支付订单ID、过期时间（创建后30分钟）
        String paymentOrderId = "PO_" + System.currentTimeMillis();
        LocalDateTime expireTime = LocalDateTime.now().plusMinutes(30);

        // 构造文档一致的响应
        return Map.of(
                "payment_order_id", paymentOrderId,
                "merchant_id", merchantId,
                "order_id", orderId,
                "amount", amount,
                "payment_status", "PENDING", // 初始状态：待支付
                "expire_time", expireTime.format(DATETIME_FORMATTER),
                "timestamp", LocalDateTime.now().format(DATETIME_FORMATTER),
                "message", "Payment order created successfully"
        );
    }

    // -------------------------- 内部辅助类（匹配响应结构） --------------------------
    @Data
    static class CardAsset {
        private String card_number;
        private String cardholder_name;
        private String bank;
        private String card_type;
        private BigDecimal credit_limit;

        public CardAsset(String cardNumber, String cardholderName, String bank, String cardType, BigDecimal creditLimit) {
            this.card_number = cardNumber;
            this.cardholder_name = cardholderName;
            this.bank = bank;
            this.card_type = cardType;
            this.credit_limit = creditLimit;
        }
    }

    @Data
    static class HouseAsset {
        private String household_id;
        private String customer_name;
        private String address;
        private String household_type;
        private BigDecimal area;
        private String ownership_type;
        private String registration_date;

        public HouseAsset(String householdId, String customerName, String address, String householdType, BigDecimal area, String ownershipType, String registrationDate) {
            this.household_id = householdId;
            this.customer_name = customerName;
            this.address = address;
            this.household_type = householdType;
            this.area = area;
            this.ownership_type = ownershipType;
            this.registration_date = registrationDate;
        }
    }

    // -------------------------- 全局异常处理（返回友好错误） --------------------------
    @ExceptionHandler(RuntimeException.class)
    public Map<String, Object> handleError(RuntimeException e) {
        log.error("Mock 服务错误：", e);
        return Map.of(
                "code", 400,
                "message", e.getMessage(),
                "timestamp", LocalDateTime.now().format(DATETIME_FORMATTER)
        );
    }
}