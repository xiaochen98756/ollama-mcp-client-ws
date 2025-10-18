package com.client.mingyuming.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.jexl3.*;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static com.client.mingyuming.service.ChatService.PARAM_ERROR_MSG;
import static com.client.mingyuming.service.ChatService.SYSTEM_ERROR_MSG;

@Slf4j
@Service
public class ToolService {
    // 新增：JEXL 引擎（单例模式，全局复用，提升性能）
    private static final JexlEngine JEXL_ENGINE = new JexlBuilder().create();

    /**
     * 本地工具：获取当前系统日期（东八区，格式：yyyy-MM-dd）
     */
    public String getCurrentDate() {
        // 东八区时区
        ZoneId zoneId = ZoneId.of("Asia/Shanghai");
        LocalDate currentDate = LocalDate.now(zoneId);
        // 格式化为 yyyy-MM-dd
        return currentDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    public String calculate(String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            return PARAM_ERROR_MSG + "：计算表达式不能为空";
        }

        try {
            // 1. 表达式预处理（统一格式，支持 sqrt、指数等）
            String processedExpr = preprocessExpression(expression);
            log.debug("预处理后的计算表达式：{}", processedExpr);

            // 2. 创建 JEXL 表达式（线程安全，可复用）
            JexlExpression jexlExpr = JEXL_ENGINE.createExpression(processedExpr);

            // 3. 执行计算（无变量传递，用空上下文）
            Object result = jexlExpr.evaluate(new MapContext());

            // 4. 格式化结果（整数转 long，避免 10.0 这类显示）
            return formatResult(result);

        } catch (JexlException e) {
            // 捕获表达式语法错误（如括号不匹配、无效运算符）
            log.error("JEXL 表达式错误：{}", expression, e);
            return PARAM_ERROR_MSG + "：无效表达式（" + e.getMessage().split("\n")[0] + "）";
        } catch (Exception e) {
            log.error("计算工具异常", e);
            return SYSTEM_ERROR_MSG;
        }
    }

    /**
     * 表达式预处理：适配 JEXL 语法，支持常见运算
     */
    private String preprocessExpression(String expr) {
        // 1. 替换 ^ 为 Math.pow(a, b)（JEXL 不支持 ^ 作为指数运算符）
        String processed = expr.replaceAll("(\\d+)\\^(\\d+)", "Math.pow($1, $2)");
        // 2. 替换 sqrt(xxx) 为 Math.sqrt(xxx)（JEXL 支持调用 Java 静态方法）
        processed = processed.replaceAll("sqrt\\(([^)]+)\\)", "Math.sqrt($1)");
        // 3. 去除多余空格（避免空格导致解析问题）
        processed = processed.replaceAll("\\s+", "");
        return processed;
    }

    /**
     * 结果格式化：处理整数/小数显示问题
     */
    private String formatResult(Object result) {
        if (result == null) {
            return "计算结果为空";
        }
        // 处理 Double 类型（如 10.0 → 10，3.75 → 3.75）
        if (result instanceof Double num) {
            return num == num.longValue() ? String.valueOf(num.longValue()) : num.toString();
        }
        // 处理整数类型（Integer/Long）
        if (result instanceof Number) {
            return result.toString();
        }
        // 其他类型（理论上不会出现）
        return "计算结果：" + result.toString();
    }
}
