package com.client.mingyuming.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static com.client.mingyuming.service.ChatService.PARAM_ERROR_MSG;
import static com.client.mingyuming.service.ChatService.SYSTEM_ERROR_MSG;

@Slf4j
@Service
public class ToolService {
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

    /**
     * 本地工具：执行数学计算（支持四则运算、平方根、指数等）
     * @param expression 数学表达式（如 "2+3*4"、"sqrt(16)"、"2^3"）
     * @return 计算结果
     */
    public String calculate(String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            return PARAM_ERROR_MSG + "：计算表达式不能为空";
        }

        try {
            // 替换^为JavaScript的指数运算符**
            String jsExpression = expression.replace("^", "**");
            // 使用JavaScript引擎执行计算
            ScriptEngineManager manager = new ScriptEngineManager();
            ScriptEngine engine = manager.getEngineByName("JavaScript");
            Object result = engine.eval(jsExpression);
            // 处理整数结果（如4.0→4）
            if (result instanceof Double) {
                Double num = (Double) result;
                if (num == num.longValue()) {
                    return String.valueOf(num.longValue());
                }
                return String.valueOf(num);
            }
            return result.toString();
        } catch (ScriptException e) {
            log.error("计算表达式错误：{}", expression, e);
            return PARAM_ERROR_MSG + "：无效的表达式（" + e.getMessage() + "）";
        } catch (Exception e) {
            log.error("计算工具异常", e);
            return SYSTEM_ERROR_MSG;
        }
    }
}
