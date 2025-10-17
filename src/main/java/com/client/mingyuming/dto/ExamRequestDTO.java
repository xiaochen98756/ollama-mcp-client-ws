package com.client.mingyuming.dto;

import lombok.Data;

/**
 * 比赛请求DTO（对应 /api/exam 接口的请求格式）
 */
@Data
public class ExamRequestDTO {
    // 比赛环节（初赛/决赛）
    private String segments;
    // 试卷类型（TEST/EXAM/TEST_HARD/EXAM_HARD）
    private String paper;
    // 试题编号
    private Integer id;
    // 题目类型（选择题/问答题）
    private String category;
    // 题目内容
    private String question;
    // 补充内容（选择题选项，工具调用题可为空）
    private String content;
}