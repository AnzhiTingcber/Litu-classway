package com.travel.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 单日行程聚合。
 * <p>
 * 由 ActivityAgent 按日期切片生成，体现「按天规划」的组合模式思路。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DayPlan {

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate date;
    private List<Activity> activities;
    private BigDecimal dayCost;
}
