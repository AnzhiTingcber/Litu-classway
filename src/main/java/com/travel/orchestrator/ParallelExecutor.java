package com.travel.orchestrator;

import com.travel.agent.ActivityAgent;
import com.travel.agent.BaseAgent;
import com.travel.agent.FlightAgent;
import com.travel.agent.HotelAgent;
import com.travel.model.PlanningState;
import com.travel.model.TraceCollector;
import com.travel.model.TraceEvent;
import com.travel.model.TravelPlanState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 并行执行器：使用 {@link CompletableFuture#allOf(CompletableFuture[])} 聚合航班/酒店/活动三类检索。
 * <p>
 * 设计模式与面试表述：
 * <ul>
 *     <li><b>并行分解</b>：无依赖的子任务同时执行，缩短端到端延迟（Amdahl 定律视角）；</li>
 *     <li><b>共享上下文</b>：三个 Agent 写入 {@link TravelPlanState} 的不同字段，需事先约定字段边界以防竞态；</li>
 *     <li><b>异常策略</b>：当前使用 {@code join()} 传播未检查异常；生产可改为 {@code handle} 或舱壁隔离。</li>
 * </ul>
 * </p>
 */
@Component
public class ParallelExecutor {

    private static final Logger log = LoggerFactory.getLogger(ParallelExecutor.class);

    private final FlightAgent flightAgent;
    private final HotelAgent hotelAgent;
    private final ActivityAgent activityAgent;
    private final Executor travelPlanningExecutor;

    public ParallelExecutor(
            FlightAgent flightAgent,
            HotelAgent hotelAgent,
            ActivityAgent activityAgent,
            @Qualifier("travelPlanningExecutor") Executor travelPlanningExecutor) {
        this.flightAgent = flightAgent;
        this.hotelAgent = hotelAgent;
        this.activityAgent = activityAgent;
        this.travelPlanningExecutor = travelPlanningExecutor;
    }

    /**
     * 并行触发三个 Agent；调用方需保证 {@code state} 已具备偏好与目的地。
     * 编排本身发布 parallel 轨迹事件（启动 + 汇合结果），与各 Agent 自己的行一起构成完整时间线。
     */
    public void runParallel(TravelPlanState state) {
        if (state.getPlanningState() == PlanningState.FAILED) {
            return;
        }
        state.setPlanningState(PlanningState.PARALLEL_SEARCH);
        log.info("CompletableFuture.allOf：并行启动 Flight / Hotel / Activity 三个 Agent");

        TraceCollector trace = state.getTrace();
        int round = state.getAdjustmentRound();
        String id = "parallel#r" + round;
        long start = System.currentTimeMillis();
        if (trace != null) {
            trace.add(new TraceEvent(start, id, TraceEvent.TYPE_PARALLEL, "三路并行检索",
                    "Flight / Hotel / Activity 经 CompletableFuture.allOf 并发执行", TraceEvent.RUNNING, 0, round));
        }

        CompletableFuture<Void> fFlight = CompletableFuture.runAsync(
                () -> runSafely(state, "FlightAgent", () -> flightAgent.run(state)), travelPlanningExecutor);
        CompletableFuture<Void> fHotel = CompletableFuture.runAsync(
                () -> runSafely(state, "HotelAgent", () -> hotelAgent.run(state)), travelPlanningExecutor);
        CompletableFuture<Void> fActivity = CompletableFuture.runAsync(
                () -> runSafely(state, "ActivityAgent", () -> activityAgent.run(state)), travelPlanningExecutor);

        CompletableFuture.allOf(fFlight, fHotel, fActivity).join();

        if (trace != null) {
            boolean failed = state.getPlanningState() == PlanningState.FAILED;
            trace.add(new TraceEvent(System.currentTimeMillis(), id, TraceEvent.TYPE_PARALLEL, "三路并行检索",
                    failed ? "并行阶段失败：" + state.getErrorMessage() : joinSummary(state),
                    failed ? TraceEvent.FAILED : TraceEvent.DONE,
                    System.currentTimeMillis() - start, round));
        }
    }

    /** 汇合结果摘要：三类候选数量 */
    private static String joinSummary(TravelPlanState state) {
        int flights = state.getFlightSearchResult() != null && state.getFlightSearchResult().getFlights() != null
                ? state.getFlightSearchResult().getFlights().size() : 0;
        int hotels = state.getHotelSearchResult() != null && state.getHotelSearchResult().getHotels() != null
                ? state.getHotelSearchResult().getHotels().size() : 0;
        int days = state.getActivitySearchResult() != null && state.getActivitySearchResult().getDayPlans() != null
                ? state.getActivitySearchResult().getDayPlans().size() : 0;
        return "并行汇合 · 航班候选 " + flights + " 个 · 酒店候选 " + hotels + " 家 · 行程编排 " + days + " 天";
    }

    /**
     * 舱壁式容错：单个 Agent 的未捕获异常（LLM 超时、网络错误等）不再通过 join() 以
     * {@code CompletionException} 拖垮整个请求，而是收敛为状态机 FAILED + errorMessage；
     * 以首个失败为准，加锁避免并发写覆盖。
     */
    private void runSafely(TravelPlanState state, String agentName, Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            log.error("{} 执行异常", agentName, e);
            synchronized (state) {
                if (state.getPlanningState() != PlanningState.FAILED) {
                    state.setPlanningState(PlanningState.FAILED);
                    state.setErrorMessage(agentName + " 执行失败: " + BaseAgent.rootMessage(e));
                }
            }
        }
    }
}
