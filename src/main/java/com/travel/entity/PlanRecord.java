package com.travel.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 规划记录（MySQL 业务数据）：表单与对话两个入口的规划都落到这一张表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("plan_record")
public class PlanRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话标识：前端 localStorage 生成的 UUID，表单与对话共用 */
    private String sessionId;

    /** 记录来源：form=表单页 / chat=对话入口 */
    private String source;

    /** 本次规划实际使用的输入（UserPreferences JSON，参数回显可对账） */
    private String inputJson;

    private String destination;

    private BigDecimal totalCost;

    /** 1=预算内 0=超支 */
    private Integer withinBudget;

    private Integer adjustmentRound;

    /** 规划状态（COMPLETE/FAILED） */
    private String status;

    /** 完整 TravelPlanState JSON（详情页用） */
    private String resultJson;

    private String errorMessage;

    private LocalDateTime createdAt;
}
