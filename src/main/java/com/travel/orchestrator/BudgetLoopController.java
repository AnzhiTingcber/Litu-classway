package com.travel.orchestrator;

import com.travel.agent.BudgetAgent;
import com.travel.model.BudgetBreakdown;
import com.travel.model.PlanningState;
import com.travel.model.TraceCollector;
import com.travel.model.TraceEvent;
import com.travel.model.TravelPlanState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 预算循环控制器：「并行检索 → 预算评估 → 超支则渐进调整 → 最多 3 轮」。
 * <p>
 * 面试 talking point：
 * <ul>
 *     <li>这是典型的 <b>反馈循环（Feedback Loop）</b>，与一次性流水线组合成「混合编排」；</li>
 *     <li>轮次上限防止无限重试（熔断/边界）；</li>
 *     <li>调整策略委托给 {@link BudgetAgent}，本类只负责流程控制（职责分离）。</li>
 * </ul>
 * </p>
 */
@Component
public class BudgetLoopController {

    private static final Logger log = LoggerFactory.getLogger(BudgetLoopController.class);
    /** 最大循环轮次（含首次并行检索） */
    private static final int MAX_ROUNDS = 3;

    private final ParallelExecutor parallelExecutor;
    private final BudgetAgent budgetAgent;

    public BudgetLoopController(ParallelExecutor parallelExecutor, BudgetAgent budgetAgent) {
        this.parallelExecutor = parallelExecutor;
        this.budgetAgent = budgetAgent;
    }

    /**
     * 执行预算闭环：可能触发多轮并行检索。
     * 每轮发布 budget 轨迹事件（轮次开始 → 结论），与并行/Agent 事件共同构成可回放时间线。
     */
    public void run(TravelPlanState state) {
        if (state.getPlanningState() == PlanningState.FAILED) {
            return;
        }
        for (int round = 0; round < MAX_ROUNDS; round++) {
            state.setAdjustmentRound(round);
            log.info("预算循环第 {} 轮（0-based），budgetPressureLevel={}", round, state.getBudgetPressureLevel());

            TraceCollector trace = state.getTrace();
            String id = "budget#r" + round;
            if (trace != null) {
                trace.add(new TraceEvent(System.currentTimeMillis(), id, TraceEvent.TYPE_BUDGET,
                        "预算闭环 · 第 " + (round + 1) + " 轮",
                        "压力等级 L" + state.getBudgetPressureLevel() + " · 并行检索进行中…",
                        TraceEvent.RUNNING, 0, round));
            }

            parallelExecutor.runParallel(state);
            if (state.getPlanningState() == PlanningState.FAILED) {
                // 并行检索失败（如 LLM 不可用）：保持 FAILED 收敛，不再进入调整/重试
                log.warn("并行检索失败，预算循环提前终止");
                if (trace != null) {
                    trace.add(new TraceEvent(System.currentTimeMillis(), id, TraceEvent.TYPE_BUDGET,
                            "预算闭环 · 第 " + (round + 1) + " 轮",
                            "并行检索失败：" + state.getErrorMessage(), TraceEvent.FAILED, 0, round));
                }
                return;
            }
            budgetAgent.evaluateAndAttach(state);

            BudgetBreakdown bd = state.getBudgetBreakdown();
            if (bd != null && bd.isWithinBudget()) {
                state.setPlanningState(PlanningState.BUDGET_RESOLVED);
                log.info("预算已通过，结束循环");
                if (trace != null) {
                    trace.add(new TraceEvent(System.currentTimeMillis(), id, TraceEvent.TYPE_BUDGET,
                            "预算闭环 · 第 " + (round + 1) + " 轮",
                            "总费用 ¥" + bd.getTotal().toPlainString() + " / 预算 ¥" + bd.getBudget().toPlainString()
                                    + " · 在预算内，通过",
                            TraceEvent.DONE, 0, round));
                }
                return;
            }

            boolean lastRound = (round == MAX_ROUNDS - 1);
            if (lastRound) {
                // 仅在评估正常时置 BUDGET_RESOLVED，避免覆盖并行阶段可能写入的 FAILED
                if (state.getPlanningState() != PlanningState.FAILED) {
                    state.setPlanningState(PlanningState.BUDGET_RESOLVED);
                    log.warn("已达最大轮次仍可能超预算，返回当前最优结果供人工决策");
                }
                if (trace != null && bd != null) {
                    trace.add(new TraceEvent(System.currentTimeMillis(), id, TraceEvent.TYPE_BUDGET,
                            "预算闭环 · 第 " + (round + 1) + " 轮",
                            "总费用 ¥" + bd.getTotal().toPlainString() + " / 预算 ¥" + bd.getBudget().toPlainString()
                                    + " · 超支 ¥" + bd.getTotal().subtract(bd.getBudget()).toPlainString()
                                    + "，已达最大轮次，返回当前最优结果供人工决策",
                            TraceEvent.DONE, 0, round));
                }
                return;
            }

            if (!budgetAgent.applyProgressiveAdjustment(state)) {
                if (state.getPlanningState() != PlanningState.FAILED) {
                    state.setPlanningState(PlanningState.BUDGET_RESOLVED);
                }
                if (trace != null && bd != null) {
                    trace.add(new TraceEvent(System.currentTimeMillis(), id, TraceEvent.TYPE_BUDGET,
                            "预算闭环 · 第 " + (round + 1) + " 轮",
                            "总费用 ¥" + bd.getTotal().toPlainString() + " / 预算 ¥" + bd.getBudget().toPlainString()
                                    + " · 超支 ¥" + bd.getTotal().subtract(bd.getBudget()).toPlainString()
                                    + "，已达最大压力等级，停止降级",
                            TraceEvent.DONE, 0, round));
                }
                return;
            }
            if (trace != null && bd != null) {
                trace.add(new TraceEvent(System.currentTimeMillis(), id, TraceEvent.TYPE_BUDGET,
                        "预算闭环 · 第 " + (round + 1) + " 轮",
                        "总费用 ¥" + bd.getTotal().toPlainString() + " / 预算 ¥" + bd.getBudget().toPlainString()
                                + " · 超支 ¥" + bd.getTotal().subtract(bd.getBudget()).toPlainString()
                                + " → 压力抬升至 L" + state.getBudgetPressureLevel() + "，重新检索",
                        TraceEvent.DONE, 0, round));
            }
        }
    }
}
