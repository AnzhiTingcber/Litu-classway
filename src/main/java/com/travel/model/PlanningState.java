package com.travel.model;

/**
 * 规划流水线状态机枚举。
 * <p>
 * 面试 talking point：用显式状态而非布尔旗标，便于观测（日志/监控）与调试；
 * 与 {@link com.travel.orchestrator.TravelPlanningPipeline}、{@link com.travel.orchestrator.BudgetLoopController} 协作描述生命周期。
 * </p>
 */
public enum PlanningState {
    /** 初始，尚未处理 */
    INITIAL,
    /** 偏好已校验与补全 */
    PREFERENCES_READY,
    /** 目的地已推荐 */
    DESTINATION_SELECTED,
    /** 并行检索进行中（航班/酒店/活动） */
    PARALLEL_SEARCH,
    /** 预算评估中 */
    BUDGET_EVALUATION,
    /** 需要因超预算而调整并重试 */
    BUDGET_ADJUSTMENT,
    /** 预算通过或已达最大轮次 */
    BUDGET_RESOLVED,
    /** 全流程成功结束 */
    COMPLETE,
    /** 输入非法或不可恢复错误 */
    FAILED
}
