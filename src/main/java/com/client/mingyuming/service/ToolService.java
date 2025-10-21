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
            // 1. 表达式预处理（适配 JEXL 实例方法调用语法）
            String processedExpr = preprocessExpression(expression);
            log.debug("预处理后的计算表达式：{}", processedExpr);

            // 2. 创建 JEXL 表达式
            JexlExpression jexlExpr = JEXL_ENGINE.createExpression(processedExpr);

            // 3. 创建上下文，注册 Math 实例（关键修复：用实例而非 Class）
            MapContext context = new MapContext();
            context.set("math", Math.class); // 注册为 "math" 实例（全小写，避免与关键字冲突）

            // 4. 执行计算
            Object result = jexlExpr.evaluate(context);

            // 5. 格式化结果
            return formatResult(result);

        } catch (JexlException e) {
            log.error("JEXL 表达式错误：{}", expression, e);
            return PARAM_ERROR_MSG + "：无效表达式（" + e.getMessage().split("\n")[0] + "）";
        } catch (Exception e) {
            log.error("计算工具异常", e);
            return SYSTEM_ERROR_MSG;
        }
    }

    /**
     * 表达式预处理：适配 JEXL 实例方法调用语法（使用 math 实例）
     */
    private String preprocessExpression(String expr) {
        // 1. 替换 ^ 为 math.pow(a, b)（注意：这里用小写 math，与上下文注册的 key 一致）
        String processed = expr.replaceAll("(\\-?\\d+(\\.\\d+)?)\\^(\\-?\\d+(\\.\\d+)?)", "math.pow($1, $3)");

        // 2. 替换 sqrt(xxx) 为 math.sqrt(xxx)
        processed = processed.replaceAll("sqrt\\(([^)]+)\\)", "math.sqrt($1)");

        // 3. 去除多余空格
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

    public static void main(String[] args) {
        ToolService service=new ToolService();
        System.out.println(service.calculate("36.18^7"));
    }
}
