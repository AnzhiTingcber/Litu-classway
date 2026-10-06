package com.travel.agent;

import com.travel.model.PlanningState;
import com.travel.model.TravelPlanState;
import com.travel.model.TravelStyle;
import com.travel.model.UserPreferences;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 偏好智能体：确定性校验 + 默认值补全。
 * <p>
 * 职责单一（SRP）：不决定目的地，只保证后续 Agent 拿到一致、合法的偏好视图。
 * 使用「卫语句」提前返回错误，避免深层嵌套。
 * </p>
 * <p>
 * 有意<b>不</b>调用 LLM：输入校验必须精确、可复现、零成本，交给概率性模型反而引入风险；
 * LLM 的语义能力用在下游（目的地选择、检索、行程生成、预算建议）。
 * </p>
 */
@Component
public class PreferenceAgent extends BaseAgent {

    private static final Map<TravelStyle, List<String>> DEFAULT_INTERESTS = new EnumMap<>(TravelStyle.class);

    static {
        DEFAULT_INTERESTS.put(TravelStyle.RELAXED, List.of("海滩", "SPA", "美食"));
        DEFAULT_INTERESTS.put(TravelStyle.ADVENTURE, List.of("徒步", "潜水", "攀岩"));
        DEFAULT_INTERESTS.put(TravelStyle.CULTURE, List.of("博物馆", "历史建筑", "当地市集"));
        DEFAULT_INTERESTS.put(TravelStyle.LUXURY, List.of("米其林", "精品购物", "私人导览"));
        DEFAULT_INTERESTS.put(TravelStyle.BUDGET_FRIENDLY, List.of("免费景点", "公共交通", "街头小吃"));
    }

    @Override
    protected void execute(TravelPlanState state) {
        UserPreferences p = state.getPreferences();
        if (p == null) {
            reject(state, "缺少用户偏好");
            return;
        }
        if (p.getBudget() == null || p.getBudget().compareTo(BigDecimal.ZERO) <= 0) {
            reject(state, "预算必须为正数");
            return;
        }
        if (p.getStartDate() == null || p.getEndDate() == null) {
            reject(state, "出行日期不能为空");
            return;
        }
        if (p.getEndDate().isBefore(p.getStartDate())) {
            reject(state, "结束日期不能早于开始日期");
            return;
        }
        if (p.getTravelers() < 1) {
            reject(state, "出行人数至少为 1");
            return;
        }
        if (p.getDepartureCity() == null || p.getDepartureCity().isBlank()) {
            reject(state, "出发城市不能为空");
            return;
        }
        if (p.getStyle() == null) {
            p.setStyle(TravelStyle.RELAXED);
            log.info("未指定旅行风格，默认 RELAXED");
        }

        long tripDays = ChronoUnit.DAYS.between(p.getStartDate(), p.getEndDate());
        if (tripDays < 1) {
            reject(state, "行程天数至少为 1 天（结束日应晚于开始日）");
            return;
        }

        if (p.getInterests() == null) {
            p.setInterests(new ArrayList<>());
        }
        if (p.getInterests().isEmpty()) {
            List<String> defs = DEFAULT_INTERESTS.getOrDefault(p.getStyle(), List.of("观光", "美食"));
            p.setInterests(new ArrayList<>(defs));
            log.info("兴趣列表为空，已按风格 {} 填充默认兴趣", p.getStyle());
        }

        state.setPlanningState(PlanningState.PREFERENCES_READY);
    }

    @Override
    protected String displayName() {
        return "PreferenceAgent · 偏好校验与补全";
    }

    @Override
    protected String runningDetail(TravelPlanState state) {
        return "卫语句校验预算/日期/人数，缺省兴趣按风格补全（不调用 LLM）";
    }

    @Override
    protected String doneDetail(TravelPlanState state) {
        UserPreferences p = state.getPreferences();
        if (p == null) {
            return "完成";
        }
        long days = p.getStartDate() != null && p.getEndDate() != null
                ? ChronoUnit.DAYS.between(p.getStartDate(), p.getEndDate()) + 1 : 0;
        return "输入合法 · " + days + " 天 " + p.getTravelers() + " 人 · 兴趣："
                + String.join("、", p.getInterests());
    }

    /** 校验失败：额外标记 validationFailure，Controller 据此返回 400（而非 502） */
    private void reject(TravelPlanState state, String msg) {
        state.setValidationFailure(true);
        super.fail(state, "输入校验失败: " + msg);
    }
}
