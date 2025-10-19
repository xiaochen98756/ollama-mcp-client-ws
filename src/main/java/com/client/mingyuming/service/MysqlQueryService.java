package com.client.mingyuming.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL 数据库查询服务：执行 SQL 并返回结构化结果
 */
@Slf4j
@Service
public class MysqlQueryService {

    private final JdbcTemplate jdbcTemplate;

    // 注入 JdbcTemplate（需在配置文件中配置数据源）
    public MysqlQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 执行 SQL 查询并返回结果
     * @param sql 待执行的 SELECT 语句
     * @return 结果集（二维列表字符串，如 "[[col1, col2], ...]"）；无结果返回 "无结果"
     */
    public String executeQuery(String sql) {
        try {
            log.info("执行 MYSQL 查询：{}", sql);

            // 执行查询并映射结果为二维列表
            List<List<String>> resultList = jdbcTemplate.query(sql, new RowMapper<List<String>>() {
                @Override
                public List<String> mapRow(ResultSet rs, int rowNum) throws SQLException {
                    List<String> row = new ArrayList<>();
                    // 获取当前行所有列的值（转为字符串）
                    int columnCount = rs.getMetaData().getColumnCount();
                    for (int i = 1; i <= columnCount; i++) {
                        row.add(rs.getString(i));
                    }
                    return row;
                }
            });

            // 处理无结果的情况
            if (resultList.isEmpty()) {
                return "无结果";
            }

            // 转换为 JSON 风格的二维列表字符串（如 "[[v1,v2],[v3,v4]]"）
            return resultList.toString();

        } catch (Exception e) {
            log.error("SQL 执行失败：{}，返回空串", sql, e);
            return "";
        }
    }
}