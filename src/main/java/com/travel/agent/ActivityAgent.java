package com.travel.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.llm.ActivityPlanAssistant;
import com.travel.agent.llm.ActivityPlanAssistant.ActivityOption;
import com.travel.agent.llm.ActivityPlanAssistant.ActivityPlan;
import com.travel.model.Activity;
import com.travel.model.ActivitySearchResult;
import com.travel.model.DayPlan;
import com.travel.model.Destination;
import com.travel.model.PlanningState;
import com.travel.model.TravelPlanState;
import com.travel.model.UserPreferences;
import com.travel.rag.CityKnowledgeBase;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 活动智能体：RAG 供给目的地真实景点知识 → LLM 按行程日逐天编排。
 * <p>
 * 行程日期由代码枚举后作为「日期清单」注入提示词，要求 LLM 严格按清单逐天输出；
 * 目的城市的真实景点/体验（含参考价）从城市知识库取出注入提示词，LLM 只做
 * 「挑选 + 分天编排 + 按预算压力取舍」，不再凭空编造景点。返回结果按日期对账：
 * 缺失或非法的日期用兜底日程补齐，保证 DayPlan 覆盖完整行程。
 * </p>
 */
@Component
public class ActivityAgent extends BaseAgent {

    private static final List<String> VALID_SLOTS = List.of("上午", "下午", "晚间");

    private final ActivityPlanAssistant assistant;
    private final CityKnowledgeBase knowledgeBase;
    private final ObjectMapper objectMapper;

    public ActivityAgent(ActivityPlanAssistant assistant,
                         CityKnowledgeBase knowledgeBase,
                         ObjectMapper objectMapper) {
        this.assistant = assistant;
        this.knowledgeBase = knowledgeBase;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void execute(TravelPlanState state) {
        if (state.getPlanningState() == PlanningState.FAILED) {
            return;
        }
        UserPreferences pref = state.getPreferences();
        Destination dest = state.getSelectedDestination();
        if (pref == null || dest == null) {
            fail(state, "缺少偏好或目的地，无法规划活动");
            return;
        }

        int pressure = state.getBudgetPressureLevel();
        LocalDate start = pref.getStartDate();
        LocalDate end = pref.getEndDate();
        String city = dest.getCity();
        List<LocalDate> dates = start.datesUntil(end).toList();

        Map<String, List<ActivityOption>> byDate;
        try {
            Map<String, Object> brief = new LinkedHashMap<>();
            brief.put("destinationCity", city);
            brief.put("dates", dates.stream().map(LocalDate::toString).toList());
            brief.put("style", pref.getStyle());
            brief.put("interests", pref.getInterests());
            brief.put("budgetPressure", pressure);
            brief.put("目的地知识库", knowledgeActivities(city));
            ActivityPlan plan = assistant.plan(objectMapper.writeValueAsString(brief));
            byDate = indexByDate(plan);
        } catch (Exception e) {
            fail(state, "活动规划 LLM 调用失败: " + rootMessage(e));
            return;
        }

        List<DayPlan> days = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (LocalDate d : dates) {
            List<Activity> acts = sanitize(byDate.get(d.toString()), d, city);
            if (acts.isEmpty()) {
                log.warn("LLM 未返回 {} 的有效活动，使用兜底日程", d);
                acts = fallbackDay(city);
            }
            BigDecimal dayCost = acts.stream()
                    .map(Activity::getPrice)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);
            total = total.add(dayCost);
            days.add(DayPlan.builder().date(d).activities(acts).dayCost(dayCost).build());
        }

        state.setActivitySearchResult(ActivitySearchResult.builder()
                .dayPlans(days)
                .totalCost(total.setScale(2, RoundingMode.HALF_UP))
                .build());

        log.info("活动规划: {} 天，活动总费用≈{}", days.size(), total);
    }

    @Override
    protected String displayName() {
        return "ActivityAgent · 活动编排";
    }

    @Override
    protected String runningDetail(TravelPlanState state) {
        return "代码枚举日期清单 → 知识库景点池注入 → LLM 逐天编排";
    }

    @Override
    protected String doneDetail(TravelPlanState state) {
        ActivitySearchResult r = state.getActivitySearchResult();
        if (r == null || r.getDayPlans() == null) {
            return "完成";
        }
        int count = r.getDayPlans().stream().mapToInt(d -> d.getActivities() != null ? d.getActivities().size() : 0).sum();
        return "编排 " + r.getDayPlans().size() + " 天共 " + count + " 项活动 · 活动费用 ¥"
                + r.getTotalCost().toPlainString();
    }

    /** 目的城市的真实景点/体验知识（本地解析，不依赖向量检索）；未收录城市返回空列表 */
    private List<String> knowledgeActivities(String city) {
        CityKnowledgeBase.CityDoc doc = knowledgeBase.city(city);
        if (doc == null) {
            return List.of();
        }
        List<String> knowledge = new ArrayList<>();
        knowledge.addAll(doc.activities().stream().limit(10).toList());
        knowledge.addAll(doc.highlights().stream().limit(5).toList());
        return knowledge;
    }

    /** 以日期字符串为键对账 LLM 返回的逐日活动 */
    private Map<String, List<ActivityOption>> indexByDate(ActivityPlan plan) {
        if (plan == null || plan.getDays() == null) {
            return Map.of();
        }
        return plan.getDays().stream()
                .filter(day -> day != null && day.getDate() != null && !day.getDate().isBlank())
                .collect(Collectors.toMap(
                        day -> day.getDate().trim(),
                        day -> day.getActivities() != null ? day.getActivities() : List.of(),
                        (a, b) -> a));
    }

    /** 净化单日活动：丢弃无名称项，钳制价格，修复非法时段 */
    private List<Activity> sanitize(List<ActivityOption> options, LocalDate date, String city) {
        if (options == null) {
            return List.of();
        }
        List<Activity> acts = new ArrayList<>();
        int index = 0;
        for (ActivityOption o : options) {
            if (o == null || o.getName() == null || o.getName().isBlank()) {
                continue;
            }
            acts.add(Activity.builder()
                    .name(o.getName().trim())
                    .category(o.getCategory() != null && !o.getCategory().isBlank() ? o.getCategory().trim() : "观光")
                    .price(BigDecimal.valueOf(Math.max(0, Math.round(o.getPrice()))))
                    .duration(o.getDuration() != null && !o.getDuration().isBlank() ? o.getDuration().trim() : "2小时")
                    .timeSlot(resolveSlot(o.getTimeSlot(), index++))
                    .build());
        }
        return acts;
    }

    private String resolveSlot(String slot, int index) {
        if (slot != null && VALID_SLOTS.stream().anyMatch(s -> slot.contains(s))) {
            return VALID_SLOTS.stream().filter(slot::contains).findFirst().orElse("上午");
        }
        return switch (index % 3) {
            case 0 -> "上午";
            case 1 -> "下午";
            default -> "晚间";
        };
    }

    /** 兜底日程：保证行程每天完整可展示 */
    private List<Activity> fallbackDay(String city) {
        return List.of(
                Activity.builder()
                        .name(city + "城市漫步")
                        .category("观光")
                        .price(BigDecimal.ZERO)
                        .duration("2小时")
                        .timeSlot("上午")
                        .build(),
                Activity.builder()
                        .name("自由活动与美食探索")
                        .category("美食")
                        .price(BigDecimal.ZERO)
                        .duration("3小时")
                        .timeSlot("晚间")
                        .build());
    }
}
