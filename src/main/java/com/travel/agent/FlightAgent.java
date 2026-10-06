package com.travel.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.llm.FlightSearchAssistant;
import com.travel.agent.llm.FlightSearchAssistant.FlightOption;
import com.travel.agent.llm.FlightSearchAssistant.FlightProposal;
import com.travel.model.Destination;
import com.travel.model.Flight;
import com.travel.model.FlightSearchResult;
import com.travel.model.PlanningState;
import com.travel.model.TravelPlanState;
import com.travel.model.UserPreferences;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 航班智能体：调用 LLM 生成候选航班并选出推荐，预算压力作为检索条件注入提示词。
 * <p>
 * 设计要点（面试可讲）：
 * <ul>
 *     <li>LLM 返回值视为「不可信输入」：航班号/航司为空、价格非正的候选直接过滤（防幻觉）；</li>
 *     <li>推荐航班号必须存在于候选列表，否则回退最低价航班（约束校验）；</li>
 *     <li>轮次间无状态：同一轮内只写自己的 {@code flightSearchResult} 字段，并行安全；
 *     调用失败走 {@link #fail} 诚实报错，不用静默假数据。</li>
 * </ul>
 * </p>
 */
@Component
public class FlightAgent extends BaseAgent {

    private final FlightSearchAssistant assistant;
    private final ObjectMapper objectMapper;

    public FlightAgent(FlightSearchAssistant assistant, ObjectMapper objectMapper) {
        this.assistant = assistant;
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
            fail(state, "缺少偏好或目的地，无法检索航班");
            return;
        }

        int pressure = state.getBudgetPressureLevel();
        int travelers = Math.max(1, pref.getTravelers());

        List<Flight> flights;
        Flight recommended;
        try {
            Map<String, Object> brief = new LinkedHashMap<>();
            brief.put("departureCity", pref.getDepartureCity());
            brief.put("destinationCity", dest.getCity());
            brief.put("departDate", pref.getStartDate().toString());
            brief.put("returnDate", pref.getEndDate().toString());
            brief.put("travelers", travelers);
            brief.put("style", pref.getStyle());
            brief.put("budgetPressure", pressure);
            FlightProposal proposal = assistant.search(objectMapper.writeValueAsString(brief));
            flights = sanitize(proposal);
            if (flights.isEmpty()) {
                fail(state, "航班检索返回数据无效（候选为空或价格非法）");
                return;
            }
            recommended = resolveRecommended(proposal.getRecommendedFlightNo(), flights);
        } catch (Exception e) {
            fail(state, "航班检索 LLM 调用失败: " + rootMessage(e));
            return;
        }

        BigDecimal roundTripPerPerson = recommended.getPrice().multiply(BigDecimal.valueOf(2));
        BigDecimal totalCost = roundTripPerPerson
                .multiply(BigDecimal.valueOf(travelers))
                .setScale(2, RoundingMode.HALF_UP);

        state.setFlightSearchResult(FlightSearchResult.builder()
                .flights(flights)
                .recommended(recommended)
                .totalCost(totalCost)
                .build());

        log.info("航班推荐: {} {}，候选 {} 个，预估总费用={}",
                recommended.getAirline(), recommended.getFlightNo(), flights.size(), totalCost);
    }

    @Override
    protected String displayName() {
        return "FlightAgent · 航班检索";
    }

    @Override
    protected String runningDetail(TravelPlanState state) {
        return "LLM 生成候选 → 净化过滤非法价格 → 推荐必须命中候选";
    }

    @Override
    protected String doneDetail(TravelPlanState state) {
        FlightSearchResult r = state.getFlightSearchResult();
        if (r == null || r.getRecommended() == null) {
            return "完成";
        }
        return "推荐 " + r.getRecommended().getFlightNo() + "（¥" + r.getRecommended().getPrice().toPlainString()
                + "/人单程）· 候选 " + r.getFlights().size() + " 个 · 压力 L" + state.getBudgetPressureLevel();
    }

    /** 净化 LLM 输出：丢弃关键字段非法的候选，钳制数值范围 */
    private List<Flight> sanitize(FlightProposal proposal) {
        if (proposal == null || proposal.getFlights() == null) {
            return List.of();
        }
        return proposal.getFlights().stream()
                .filter(o -> o != null && notBlank(o.getFlightNo()) && notBlank(o.getAirline()) && o.getPrice() > 0)
                .map(o -> Flight.builder()
                        .airline(o.getAirline().trim())
                        .flightNo(o.getFlightNo().trim())
                        .price(BigDecimal.valueOf(Math.round(o.getPrice())))
                        .duration(notBlank(o.getDuration()) ? o.getDuration().trim() : "6h00m")
                        .stops(Math.max(0, Math.min(2, o.getStops())))
                        .build())
                .toList();
    }

    private Flight resolveRecommended(String recommendedFlightNo, List<Flight> flights) {
        if (notBlank(recommendedFlightNo)) {
            String no = recommendedFlightNo.trim();
            var matched = flights.stream()
                    .filter(f -> f.getFlightNo().equals(no))
                    .findFirst();
            if (matched.isPresent()) {
                return matched.get();
            }
            log.warn("LLM 推荐航班号 [{}] 不在候选列表中，回退最低价航班", no);
        }
        return flights.stream()
                .min(Comparator.comparing(Flight::getPrice))
                .orElse(flights.getFirst());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
