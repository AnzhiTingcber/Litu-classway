package com.travel.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 活动/每日行程检索结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivitySearchResult {

    private List<DayPlan> dayPlans;
    private BigDecimal totalCost;
}
