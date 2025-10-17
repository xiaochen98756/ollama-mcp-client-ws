package com.client.mingyuming.dto;

import lombok.Data;

/**
 * 比赛响应DTO（对应 /api/exam 接口的响应格式）
 */
@Data
public class ExamResponseDTO {
    // 与请求一致的比赛环节
    private String segments;
    // 与请求一致的试卷类型
    private String paper;
    // 与请求一致的试题编号
    private Integer id;
    // 工具调用结果/答案（需符合比赛格式要求）
    private String answer;
}