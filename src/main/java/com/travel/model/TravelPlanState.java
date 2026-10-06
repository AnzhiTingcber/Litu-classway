package com.travel.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 贯穿流水线的共享上下文（类似「黑板模式 / Shared Context」）。
 * <p>
 * 面试 talking point：
 * <ul>
 *     <li>各 Agent 只读写自己负责的字段，并行阶段（航班/酒店/活动）互不覆盖，保证线程安全；</li>
 *     <li>{@code budgetPressureLevel} 由 BudgetAgent 递增，驱动 mock 数据逐轮降价，演示「反馈循环」；</li>
 *     <li>状态机字段 {@link #planningState} 用于可观测性与调试。</li>
 * </ul>
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TravelPlanState {

    private UserPreferences preferences;
    private Destination selectedDestination;

    private FlightSearchResult flightSearchResult;
    private HotelSearchResult hotelSearchResult;
    private ActivitySearchResult activitySearchResult;

    private BudgetBreakdown budgetBreakdown;

    private PlanningState planningState;

    /** 目的地推荐理由（LLM 生成，可空） */
    private String selectionReason;

    /** 预算循环当前轮次（0-based） */
    private int adjustmentRound;

    /**
     * 预算压力等级：0 正常检索，1 适度降级，2 强降级（更多经停、低星酒店、减少付费活动等 mock 行为）。
     */
    @Builder.Default
    private int budgetPressureLevel = 0;

    /** true 表示因输入校验失败而 FAILED（HTTP 400）；否则为规划/LLM 调用失败（HTTP 502） */
    @Builder.Default
    private boolean validationFailure = false;

    /** MySQL 规划记录 ID（落库成功后回填，前端凭此跳转详情） */
    private Long planRecordId;

    /** 人类可读的错误信息（如校验失败、LLM 调用失败） */
    private String errorMessage;

    /** 执行轨迹：各智能体与编排步骤的事件流，随结果返回并落库（历史可回放真实执行过程） */
    @Builder.Default
    private TraceCollector trace = new TraceCollector();
}
