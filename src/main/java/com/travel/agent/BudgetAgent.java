package com.travel.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.llm.BudgetAdviceAssistant;
import com.travel.agent.llm.BudgetAdviceAssistant.BudgetAdvice;
import com.travel.model.BudgetBreakdown;
import com.travel.model.FlightSearchResult;
import com.travel.model.HotelSearchResult;
import com.travel.model.ActivitySearchResult;
import com.travel.model.PlanningState;
import com.travel.model.TravelPlanState;
import com.travel.model.UserPreferences;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预算智能体：确定性聚合成本与预算对比，超支时由 LLM 生成调整建议并驱动「渐进式降级」。
 * <p>
 * 设计说明（面试可讲）：
 * <ul>
 *     <li><b>金额计算不走 LLM</b>：汇总与对比必须精确、可复现，用 {@link BigDecimal} 确定性完成；
 *     LLM 只负责语义层——生成有针对性、量化的调优建议；</li>
 *     <li>{@link #applyProgressiveAdjustment} 只修改压力等级与状态机，
 *     由下一轮并行检索让 LLM 在更强省钱约束下重新生成方案——真正的「反馈循环」；</li>
 *     <li>建议生成失败自动回退固定建议，评估结论不受影响。</li>
 * </ul>
 * </p>
 */
@Component
public class BudgetAgent extends BaseAgent {

    private static final int MAX_PRESSURE = 2;

    private final BudgetAdviceAssistant adviceAssistant;
    private final ObjectMapper objectMapper;

    public BudgetAgent(BudgetAdviceAssistant adviceAssistant, ObjectMapper objectMapper) {
        this.adviceAssistant = adviceAssistant;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void execute(TravelPlanState state) {
        evaluateAndAttach(state);
    }

    /**
     * 供预算循环显式调用：写入 {@link BudgetBreakdown} 并更新是否超预算。
     */
    public void evaluateAndAttach(TravelPlanState state) {
        if (state.getPlanningState() == PlanningState.FAILED) {
            return;
        }
        UserPreferences pref = state.getPreferences();
        if (pref == null) {
            return;
        }
        BigDecimal budget = pref.getBudget();
        BigDecimal flight = safeFlight(state.getFlightSearchResult());
        BigDecimal hotel = safeHotel(state.getHotelSearchResult());
        BigDecimal act = safeActivity(state.getActivitySearchResult());
        BigDecimal total = flight.add(hotel).add(act).setScale(2, RoundingMode.HALF_UP);
        boolean ok = total.compareTo(budget) <= 0;

        List<String> suggestions = new ArrayList<>();
        if (!ok) {
            suggestions.addAll(buildSuggestions(state, budget, total, flight, hotel, act));
        }

        state.setBudgetBreakdown(BudgetBreakdown.builder()
                .flightCost(flight)
                .hotelCost(hotel)
                .activityCost(act)
                .total(total)
                .budget(budget)
                .withinBudget(ok)
                .suggestions(suggestions)
                .build());
        state.setPlanningState(PlanningState.BUDGET_EVALUATION);
        log.info("预算评估: total={} budget={} within={}", total, budget, ok);
    }

    /** 超支时的调整建议：优先 LLM 生成（结合超支金额、目的城市与当前压力等级），失败回退固定文案 */
    private List<String> buildSuggestions(TravelPlanState state, BigDecimal budget, BigDecimal total,
                                          BigDecimal flight, BigDecimal hotel, BigDecimal act) {
        try {
            Map<String, Object> brief = new LinkedHashMap<>();
            brief.put("budget", budget);
            brief.put("totalCost", total);
            brief.put("flightCost", flight);
            brief.put("hotelCost", hotel);
            brief.put("activityCost", act);
            brief.put("destination", state.getSelectedDestination() != null
                    ? state.getSelectedDestination().getCity() : null);
            brief.put("style", state.getPreferences() != null ? state.getPreferences().getStyle() : null);
            brief.put("budgetPressureLevel", state.getBudgetPressureLevel());
            brief.put("adjustmentRound", state.getAdjustmentRound());
            BudgetAdvice advice = adviceAssistant.advise(objectMapper.writeValueAsString(brief));
            if (advice != null && advice.getSuggestions() != null) {
                List<String> cleaned = advice.getSuggestions().stream()
                        .filter(s -> s != null && !s.isBlank())
                        .limit(5)
                        .toList();
                if (!cleaned.isEmpty()) {
                    return cleaned;
                }
            }
        } catch (Exception e) {
            log.warn("预算建议 LLM 调用失败，使用固定建议: {}", rootMessage(e));
        }
        return List.of(
                "总费用 " + total + " 超出预算 " + budget + "，建议接受降级检索或缩短行程。",
                "可优先：更多经停航班、低星级酒店、减少付费门票与购物项。");
    }

    private BigDecimal safeFlight(FlightSearchResult r) {
        return r != null && r.getTotalCost() != null ? r.getTotalCost() : BigDecimal.ZERO;
    }

    private BigDecimal safeHotel(HotelSearchResult r) {
        return r != null && r.getTotalCost() != null ? r.getTotalCost() : BigDecimal.ZERO;
    }

    private BigDecimal safeActivity(ActivitySearchResult r) {
        return r != null && r.getTotalCost() != null ? r.getTotalCost() : BigDecimal.ZERO;
    }

    /**
     * @return true 表示已应用调整且仍可继续下一轮检索；false 表示已达调整上限或已在预算内
     */
    public boolean applyProgressiveAdjustment(TravelPlanState state) {
        BudgetBreakdown bd = state.getBudgetBreakdown();
        if (bd != null && bd.isWithinBudget()) {
            return false;
        }
        int next = state.getBudgetPressureLevel() + 1;
        if (next > MAX_PRESSURE) {
            log.warn("已达最大预算压力等级，停止继续降级");
            return false;
        }
        state.setBudgetPressureLevel(next);
        state.setPlanningState(PlanningState.BUDGET_ADJUSTMENT);
        log.info("应用渐进式预算调整: budgetPressureLevel={}", next);
        return true;
    }
}
