package com.travel.orchestrator;

import com.travel.agent.DestinationAgent;
import com.travel.agent.PreferenceAgent;
import com.travel.model.PlanningState;
import com.travel.model.TraceCollector;
import com.travel.model.TraceEvent;
import com.travel.model.TravelPlanState;
import com.travel.model.UserPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * 总编排流水线：Preference → Destination → BudgetLoop（内含并行与预算反馈）。
 * <p>
 * 架构总结（可面试口述）：
 * <ol>
 *     <li><b>顺序阶段</b>：偏好与目的地存在数据依赖，必须串行；</li>
 *     <li><b>并行阶段</b>：航班/酒店/活动在 mock 假设下互不依赖，可 {@code allOf}；</li>
 *     <li><b>循环阶段</b>：预算 Agent 作为「守卫」，超支则抬高 {@code budgetPressureLevel} 再检索。</li>
 * </ol>
 * 该混合模式在真实系统中可映射为 DAG 工作流引擎或 Temporal/Camunda 等编排工具。
 * </p>
 */
@Component
public class TravelPlanningPipeline {

    private static final Logger log = LoggerFactory.getLogger(TravelPlanningPipeline.class);

    private final PreferenceAgent preferenceAgent;
    private final DestinationAgent destinationAgent;
    private final BudgetLoopController budgetLoopController;

    public TravelPlanningPipeline(
            PreferenceAgent preferenceAgent,
            DestinationAgent destinationAgent,
            BudgetLoopController budgetLoopController) {
        this.preferenceAgent = preferenceAgent;
        this.destinationAgent = destinationAgent;
        this.budgetLoopController = budgetLoopController;
    }

    /**
     * @param rawPreferences 来自 API 的原始偏好（会被各 Agent 读取/补全）
     * @param traceListener  轨迹实时监听者（对话入口用于把事件推到 SSE；可为 null）
     * @return 聚合后的规划状态
     */
    public TravelPlanState execute(UserPreferences rawPreferences, Consumer<TraceEvent> traceListener) {
        TraceCollector trace = new TraceCollector();
        if (traceListener != null) {
            trace.addListener(traceListener);
        }
        TravelPlanState state = TravelPlanState.builder()
                .preferences(rawPreferences)
                .planningState(PlanningState.INITIAL)
                .adjustmentRound(0)
                .budgetPressureLevel(0)
                .trace(trace)
                .build();

        long start = System.currentTimeMillis();
        String pipelineId = "pipeline";
        trace.add(new TraceEvent(start, pipelineId, TraceEvent.TYPE_PIPELINE, "多智能体流水线",
                "偏好校验 → 目的地甄选 → 三路并行检索 → 预算闭环", TraceEvent.RUNNING, 0, 0));

        log.info("流水线开始：PreferenceAgent");
        preferenceAgent.run(state);
        if (state.getPlanningState() == PlanningState.FAILED) {
            finishTrace(trace, pipelineId, start, state);
            return state;
        }

        log.info("流水线：DestinationAgent");
        destinationAgent.run(state);
        if (state.getPlanningState() == PlanningState.FAILED) {
            finishTrace(trace, pipelineId, start, state);
            return state;
        }

        log.info("流水线：BudgetLoop（并行 + 预算）");
        budgetLoopController.run(state);

        if (state.getPlanningState() != PlanningState.FAILED) {
            state.setPlanningState(PlanningState.COMPLETE);
        }
        finishTrace(trace, pipelineId, start, state);
        log.info("流水线结束，状态={}", state.getPlanningState());
        return state;
    }

    /** 流水线级收尾事件：总耗时 + 最终状态（含目的地），前端凭它关闭整条轨迹 */
    private static void finishTrace(TraceCollector trace, String pipelineId, long start, TravelPlanState state) {
        boolean failed = state.getPlanningState() == PlanningState.FAILED;
        String dest = state.getSelectedDestination() != null ? "目的地「" + state.getSelectedDestination().getCity() + "」" : "";
        trace.add(new TraceEvent(System.currentTimeMillis(), pipelineId, TraceEvent.TYPE_PIPELINE, "多智能体流水线",
                (failed ? "失败：" + state.getErrorMessage() : "完成 · " + dest + "总耗时 "
                        + (System.currentTimeMillis() - start) / 1000.0 + "s"),
                failed ? TraceEvent.FAILED : TraceEvent.DONE,
                System.currentTimeMillis() - start, state.getAdjustmentRound()));
    }
}
