package com.travel.service;

import com.travel.model.TraceEvent;
import com.travel.model.TravelPlanState;
import com.travel.model.UserPreferences;
import com.travel.orchestrator.TravelPlanningPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * 应用服务层：对外暴露「生成行程」用例，隐藏编排细节。
 * 表单与对话两个入口都经由本方法，规划记录在出口统一落 MySQL（来源字段区分入口）。
 */
@Service
public class TravelPlanService {

    private static final Logger log = LoggerFactory.getLogger(TravelPlanService.class);

    private final TravelPlanningPipeline pipeline;
    private final PlanRecordService planRecordService;
    private final TracePublisher tracePublisher;

    public TravelPlanService(TravelPlanningPipeline pipeline, PlanRecordService planRecordService,
                             TracePublisher tracePublisher) {
        this.pipeline = pipeline;
        this.planRecordService = planRecordService;
        this.tracePublisher = tracePublisher;
    }

    /**
     * 执行完整多智能体规划并落库。
     *
     * @param sessionId 会话标识（表单页/对话页共用同一 UUID，用于历史聚合；可空）
     * @param source    记录来源：form / chat
     */
    public TravelPlanState plan(UserPreferences preferences, String sessionId, String source) {
        log.info("TravelPlanService 接收规划请求: 出发={} 人数={} 来源={} 会话={}",
                preferences != null ? preferences.getDepartureCity() : null,
                preferences != null ? preferences.getTravelers() : 0,
                source,
                sessionId != null ? sessionId : "anonymous");
        // 对话入口把轨迹事件实时转发到该会话的 SSE 订阅者；表单入口无监听者，仅随结果落库
        Consumer<TraceEvent> traceListener = "chat".equals(source) && sessionId != null && !sessionId.isBlank()
                ? event -> tracePublisher.publish(sessionId.trim(), event)
                : null;
        TravelPlanState state = pipeline.execute(preferences, traceListener);
        Long recordId = planRecordService.save(sessionId, source, preferences, state);
        if (recordId != null) {
            state.setPlanRecordId(recordId);
        }
        return state;
    }
}
