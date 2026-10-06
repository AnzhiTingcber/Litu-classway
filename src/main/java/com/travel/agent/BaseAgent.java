package com.travel.agent;

import com.travel.model.PlanningState;
import com.travel.model.TraceCollector;
import com.travel.model.TraceEvent;
import com.travel.model.TravelPlanState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 智能体抽象基类：模板方法模式（Template Method）。
 * <p>
 * {@link #run(TravelPlanState)} 固定骨架（日志、扩展点可加入计时/熔断），
 * {@link #execute(TravelPlanState)} 由子类实现具体业务。
 * </p>
 * <p>
 * 面试要点：与「策略模式」区别——模板方法强调算法步骤不可变、部分步骤可覆盖；
 * 若把 Agent 换成接口 + 多实现，则更偏策略（可互换算法）。
 * </p>
 */
public abstract class BaseAgent {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * 对外入口：发布 RUNNING 轨迹 → 执行子类逻辑 → 按 收敛结果/异常 发布 DONE 或 FAILED 轨迹。
     * 轨迹随 {@code state.trace} 累积（落库回放）并实时转发（对话入口 SSE）。
     */
    public final void run(TravelPlanState state) {
        log.debug("Agent [{}] 开始执行，当前状态={}", getClass().getSimpleName(), state.getPlanningState());
        TraceCollector trace = state.getTrace();
        int round = state.getAdjustmentRound();
        String id = getClass().getSimpleName() + "#r" + round;
        long start = System.currentTimeMillis();
        if (trace != null) {
            trace.add(new TraceEvent(start, id, TraceEvent.TYPE_AGENT, displayName(),
                    runningDetail(state), TraceEvent.RUNNING, 0, round));
        }
        try {
            execute(state);
        } catch (RuntimeException e) {
            if (trace != null) {
                trace.add(new TraceEvent(System.currentTimeMillis(), id, TraceEvent.TYPE_AGENT, displayName(),
                        "执行异常: " + rootMessage(e), TraceEvent.FAILED, elapsed(start), round));
            }
            throw e;
        }
        if (trace != null) {
            boolean failed = state.getPlanningState() == PlanningState.FAILED;
            trace.add(new TraceEvent(System.currentTimeMillis(), id, TraceEvent.TYPE_AGENT, displayName(),
                    failed ? state.getErrorMessage() : doneDetail(state),
                    failed ? TraceEvent.FAILED : TraceEvent.DONE, elapsed(start), round));
        }
        log.debug("Agent [{}] 执行结束", getClass().getSimpleName());
    }

    /** 轨迹展示名（前端步骤行标题），子类覆盖为「类名 · 中文职责」 */
    protected String displayName() {
        return getClass().getSimpleName();
    }

    /** RUNNING 行的说明文案，子类可覆盖 */
    protected String runningDetail(TravelPlanState state) {
        return "执行中…";
    }

    /** DONE 行的结果摘要，子类可覆盖 */
    protected String doneDetail(TravelPlanState state) {
        return "完成";
    }

    private static long elapsed(long startMillis) {
        return System.currentTimeMillis() - startMillis;
    }

    /**
     * 子类实现的核心逻辑。
     */
    protected abstract void execute(TravelPlanState state);

    /**
     * 统一失败出口：置状态机为 FAILED 并记录人类可读原因（Controller 依据该状态返回 4xx/5xx）。
     */
    protected final void fail(TravelPlanState state, String message) {
        log.warn("Agent [{}] 失败: {}", getClass().getSimpleName(), message);
        state.setPlanningState(PlanningState.FAILED);
        state.setErrorMessage(message);
    }

    /**
     * 提取异常根因信息，避免日志/响应里出现 null 或冗长的包装异常名。
     */
    public static String rootMessage(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
