package com.travel.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 预算拆解与建议。
 * <p>
 * BudgetAgent 责任：聚合三类成本、对比总预算、生成可读建议（策略模式可进一步拆分不同建议生成器）。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BudgetBreakdown {

    private BigDecimal flightCost;
    private BigDecimal hotelCost;
    private BigDecimal activityCost;
    private BigDecimal total;
    private BigDecimal budget;
    private boolean withinBudget;

    @Builder.Default
    private List<String> suggestions = new ArrayList<>();
}
