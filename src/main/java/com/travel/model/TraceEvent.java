package com.travel.model;

/**
 * 执行轨迹事件：一次规划运行中某个步骤的单条记录（开始/完成/失败）。
 * <p>
 * {@code id} 是步骤的稳定标识（如 {@code DestinationAgent#r0}、{@code budget#r1}），
 * 前端凭它把 RUNNING 行原地更新为 DONE/FAILED；{@code round} 标记预算循环轮次。
 * 事件随 {@link TravelPlanState} 序列化进规划结果（落库 + 返回前端），
 * 对话入口同时经监听者实时推送到 SSE。
 * </p>
 */
public record TraceEvent(
        long ts,
        String id,
        String type,
        String name,
        String detail,
        String status,
        long durationMs,
        int round) {

    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";

    /** 事件类型：agent=智能体步骤，parallel=并行编排，budget=预算闭环，pipeline=流水线级，tool=LLM 工具调用 */
    public static final String TYPE_AGENT = "agent";
    public static final String TYPE_PARALLEL = "parallel";
    public static final String TYPE_BUDGET = "budget";
    public static final String TYPE_PIPELINE = "pipeline";
    public static final String TYPE_TOOL = "tool";
}
