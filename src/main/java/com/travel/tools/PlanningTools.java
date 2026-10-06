package com.travel.tools;

import com.travel.agent.TravelChatAssistant;
import com.travel.entity.PlanRecord;
import com.travel.model.BudgetBreakdown;
import com.travel.model.TraceEvent;
import com.travel.model.TravelPlanState;
import com.travel.model.TravelStyle;
import com.travel.model.UserPreferences;
import com.travel.service.PlanRecordService;
import com.travel.service.TracePublisher;
import com.travel.service.TravelPlanService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 规划工具：把现有多智能体流水线暴露为 LLM 可调用的 @Tool。
 * <p>
 * 设计要点（面试可讲）：
 * <ul>
 *     <li>LLM 只负责"何时调用 + 填参数"，规划本身仍是确定性流水线 + RAG，不因对话而变味；</li>
 *     <li>sessionId 由框架注入：工具方法声明 {@code @ToolMemoryId} 参数——Flux 流式下工具在异步线程执行，
 *     ThreadLocal 不可靠（同步改流式时踩过的真实坑），框架透传自然带会话标识；</li>
 *     <li>参数非法（日期格式/风格枚举）时返回可读错误，让 LLM 向用户追问而不是硬跑。</li>
 * </ul>
 * </p>
 */
@Component
public class PlanningTools {

    private static final Logger log = LoggerFactory.getLogger(PlanningTools.class);

    private final TravelPlanService travelPlanService;
    private final TracePublisher tracePublisher;
    private final PlanRecordService planRecordService;

    public PlanningTools(TravelPlanService travelPlanService, TracePublisher tracePublisher,
                         PlanRecordService planRecordService) {
        this.travelPlanService = travelPlanService;
        this.tracePublisher = tracePublisher;
        this.planRecordService = planRecordService;
    }

    private static BigDecimal bd(TravelPlanState state) {
        BudgetBreakdown b = state.getBudgetBreakdown();
        return b != null ? b.getTotal() : null;
    }

    @Tool("""
            发起一次完整的旅游行程规划（多智能体流水线：RAG 选目的地 → 并行检索航班/酒店/活动 → 预算闭环，约 40~150 秒）。
            仅当预算、出发城市、出发与返回日期、出行人数都已知时才调用；任一缺失请先向用户追问。
            """)
    public String planTravel(
            @ToolMemoryId String sessionId,
            @P("总预算，数字，单位人民币") double budget,
            @P("出发城市，中文城市名") String departureCity,
            @P("出发日期，格式 yyyy-MM-dd") String startDate,
            @P("返回日期，格式 yyyy-MM-dd，须晚于出发日期") String endDate,
            @P("出行人数，正整数") int travelers,
            @P("旅行风格，取值：RELAXED/ADVENTURE/CULTURE/LUXURY/BUDGET_FRIENDLY") String style,
            @P("兴趣标签，中文，用逗号分隔；用户未提及则传空字符串") String interests) {

        UserPreferences prefs = new UserPreferences();
        try {
            prefs.setBudget(BigDecimal.valueOf(budget));
            prefs.setDepartureCity(departureCity);
            prefs.setStartDate(LocalDate.parse(startDate.trim()));
            prefs.setEndDate(LocalDate.parse(endDate.trim()));
        } catch (DateTimeParseException e) {
            return "参数错误：日期格式必须是 yyyy-MM-dd，请与用户确认后重试。";
        }
        prefs.setTravelers(travelers);
        try {
            prefs.setStyle(TravelStyle.valueOf(style.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return "参数错误：旅行风格只能是 RELAXED/ADVENTURE/CULTURE/LUXURY/BUDGET_FRIENDLY，请与用户确认。";
        }
        if (interests != null && !interests.isBlank()) {
            List<String> tags = Arrays.stream(interests.split("[、,，]")).map(String::trim)
                    .filter(s -> !s.isEmpty()).toList();
            prefs.setInterests(new ArrayList<>(tags));
        }

        log.info("对话触发规划: 预算={} {} 人数={} 会话={}", budget, departureCity, travelers, sessionId);
        long start = System.currentTimeMillis();
        // 工具 RUNNING 事件先直接推送（此时流水线尚未创建轨迹收集器，无法落库，仅实时展示）
        tracePublisher.publish(sessionId, new TraceEvent(start, "tool:planTravel", TraceEvent.TYPE_TOOL,
                "planTravel · 发起规划流水线",
                "预算 ¥" + budget + " · " + departureCity + " 出发 · " + startDate + " ~ " + endDate
                        + " · " + travelers + " 人 · " + style
                        + (interests != null && !interests.isBlank() ? " · 兴趣：" + interests : ""),
                TraceEvent.RUNNING, 0, 0));

        TravelPlanState state = travelPlanService.plan(prefs, sessionId, "chat");

        // 完成/失败事件写入 state 轨迹：实时推送 + 随结果落库回放
        if (state.getTrace() != null) {
            boolean failed = state.getPlanningState() == com.travel.model.PlanningState.FAILED;
            String dest = state.getSelectedDestination() != null
                    ? "目的地「" + state.getSelectedDestination().getCity() + "」" : "";
            state.getTrace().add(new TraceEvent(System.currentTimeMillis(), "tool:planTravel",
                    TraceEvent.TYPE_TOOL, "planTravel · 发起规划流水线",
                    failed ? "规划失败：" + state.getErrorMessage()
                            : "规划完成 · " + dest + "总费用 ¥" + (bd(state) != null ? bd(state).toPlainString() : "?"),
                    failed ? TraceEvent.FAILED : TraceEvent.DONE,
                    System.currentTimeMillis() - start, state.getAdjustmentRound()));
        }

        if (state.getPlanningState() == com.travel.model.PlanningState.FAILED) {
            return "规划失败：" + state.getErrorMessage() + "。请根据原因与用户确认修正后重试。";
        }
        BudgetBreakdown bd = state.getBudgetBreakdown();
        String dest = state.getSelectedDestination() != null ? state.getSelectedDestination().getCity() : "未知";
        String reason = state.getSelectionReason() != null ? state.getSelectionReason() : "";
        return "规划完成！目的地：" + dest
                + (reason.isBlank() ? "" : "（" + reason + "）")
                + "。总费用 " + (bd != null ? bd.getTotal().toPlainString() : "?") + " 元 / 预算 "
                + prefs.getBudget().toPlainString() + " 元，"
                + (bd != null && bd.isWithinBudget() ? "在预算内" : "超支（已达最大降级轮次，附调整建议）")
                + (state.getAdjustmentRound() > 0 ? "，经 " + state.getAdjustmentRound() + " 轮预算降级" : "")
                + (state.getPlanRecordId() != null
                        ? "。本次规划记录编号 " + state.getPlanRecordId() + "（用户后续可凭编号查询或删除）" : "")
                + "。完整行程单与预算测算已在「行程规划」页可见。";
    }

    /** 查询本会话历史规划单：删除/核对前的定位依据（按 sessionId 天然隔离，看不到别人的记录） */
    @Tool("""
            查询当前会话的全部历史规划单（记录编号、目的地、总费用、状态、日期、来源）。
            用户想删除规划单或核对某次规划时，先调用本工具拿到记录编号。
            """)
    public String listMyPlans(@ToolMemoryId String sessionId) {
        List<PlanRecord> records = planRecordService.listBySession(sessionId);
        if (records.isEmpty()) {
            return "当前会话还没有规划记录。";
        }
        StringBuilder sb = new StringBuilder("当前会话共 ").append(records.size()).append(" 条规划记录：\n");
        for (PlanRecord r : records) {
            sb.append("- 编号 ").append(r.getId())
                    .append("：").append(r.getDestination() != null ? r.getDestination() : "未出目的地")
                    .append("，总费用 ").append(r.getTotalCost() != null ? r.getTotalCost().toPlainString() + " 元" : "—")
                    .append("，").append(r.getStatus() != null ? r.getStatus() : "未知")
                    .append("，").append(r.getCreatedAt() != null ? r.getCreatedAt().toLocalDate().toString() : "")
                    .append("（来源 ").append(r.getSource()).append("）\n");
        }
        return sb.toString();
    }

    /**
     * 删除本会话的一条规划单。参照 xiaozhi 项目「取消预约」工具模式，但更严谨：
     * sessionId 作用域隔离（别人的删不了）+ 先查后删 + 确认话术由系统提示词与工具描述双重约束。
     */
    @Tool("""
            删除当前会话的一条历史规划单（按记录编号）。
            调用前必须先向用户复述待删除的记录（编号、目的地、总费用），得到用户明确确认后才可调用；
            用户未确认时不得调用本工具。只能删除本会话内产生的记录。
            """)
    public String deletePlan(@ToolMemoryId String sessionId,
                             @P("规划记录编号，来自规划完成时返回的编号或 listMyPlans 查询结果") long planId) {
        PlanRecord record = planRecordService.getById(planId);
        if (record == null) {
            return "编号 " + planId + " 的规划记录不存在或已删除。";
        }
        if (sessionId == null || !sessionId.equals(record.getSessionId())) {
            return "只能删除本会话内产生的规划记录，编号 " + planId + " 不属于当前会话。";
        }
        String desc = record.getDestination() != null ? record.getDestination() : "未出目的地";
        if (planRecordService.deleteById(record.getId())) {
            log.info("对话删除规划记录: id={} 会话={} 目的地={}", record.getId(), sessionId, desc);
            return "已删除编号 " + planId + " 的规划记录（" + desc + "）。";
        }
        return "删除失败，请稍后重试。";
    }
}
