package com.travel.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.llm.HotelSearchAssistant;
import com.travel.agent.llm.HotelSearchAssistant.HotelOption;
import com.travel.agent.llm.HotelSearchAssistant.HotelProposal;
import com.travel.model.Destination;
import com.travel.model.Hotel;
import com.travel.model.HotelSearchResult;
import com.travel.model.PlanningState;
import com.travel.model.TravelPlanState;
import com.travel.model.UserPreferences;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 酒店智能体：调用 LLM 生成多档候选酒店并选出推荐，晚数/间数由确定性代码计算。
 * <p>
 * 预算压力作为检索条件注入提示词（0=舒适，1=均衡，2=省钱），驱动 LLM 逐轮给出
 * 更低档位与价格的方案——「渐进式调整」对供给侧的影响由真实 LLM 推理产生，而非算术降级。
 * LLM 输出同样经过净化：名称为空、房价非正的候选被过滤，星级钳制在 2~5。
 * </p>
 */
@Component
public class HotelAgent extends BaseAgent {

    private final HotelSearchAssistant assistant;
    private final ObjectMapper objectMapper;

    public HotelAgent(HotelSearchAssistant assistant, ObjectMapper objectMapper) {
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
            fail(state, "缺少偏好或目的地，无法检索酒店");
            return;
        }

        int pressure = state.getBudgetPressureLevel();
        long nights = Math.max(1, ChronoUnit.DAYS.between(pref.getStartDate(), pref.getEndDate()));
        int travelers = Math.max(1, pref.getTravelers());
        int rooms = Math.max(1, (travelers + 1) / 2);

        List<Hotel> hotels;
        Hotel recommended;
        try {
            Map<String, Object> brief = new LinkedHashMap<>();
            brief.put("destinationCity", dest.getCity());
            brief.put("checkIn", pref.getStartDate().toString());
            brief.put("checkOut", pref.getEndDate().toString());
            brief.put("nights", nights);
            brief.put("rooms", rooms);
            brief.put("travelers", travelers);
            brief.put("style", pref.getStyle());
            brief.put("interests", pref.getInterests());
            brief.put("budgetPressure", pressure);
            HotelProposal proposal = assistant.search(objectMapper.writeValueAsString(brief));
            hotels = sanitize(proposal, dest.getCity());
            if (hotels.isEmpty()) {
                fail(state, "酒店检索返回数据无效（候选为空或房价非法）");
                return;
            }
            recommended = resolveRecommended(proposal.getRecommendedHotelName(), hotels);
        } catch (Exception e) {
            fail(state, "酒店检索 LLM 调用失败: " + rootMessage(e));
            return;
        }

        BigDecimal totalCost = recommended.getPricePerNight()
                .multiply(BigDecimal.valueOf(nights))
                .multiply(BigDecimal.valueOf(rooms))
                .setScale(2, RoundingMode.HALF_UP);

        state.setHotelSearchResult(HotelSearchResult.builder()
                .hotels(hotels)
                .recommended(recommended)
                .totalCost(totalCost)
                .build());

        log.info("酒店推荐: {}，{} 晚 × {} 间，候选 {} 个，预估={}",
                recommended.getName(), nights, rooms, hotels.size(), totalCost);
    }

    @Override
    protected String displayName() {
        return "HotelAgent · 酒店检索";
    }

    @Override
    protected String runningDetail(TravelPlanState state) {
        return "晚数/间数由代码计算 → LLM 生成候选 → 净化钳制";
    }

    @Override
    protected String doneDetail(TravelPlanState state) {
        HotelSearchResult r = state.getHotelSearchResult();
        if (r == null || r.getRecommended() == null) {
            return "完成";
        }
        return "推荐 " + r.getRecommended().getName() + "（¥" + r.getRecommended().getPricePerNight().toPlainString()
                + "/晚）· 候选 " + r.getHotels().size() + " 家 · 压力 L" + state.getBudgetPressureLevel();
    }

    /** 净化 LLM 输出：丢弃名称为空或房价非法的候选，钳制星级，补齐缺省设施列表 */
    private List<Hotel> sanitize(HotelProposal proposal, String city) {
        if (proposal == null || proposal.getHotels() == null) {
            return List.of();
        }
        return proposal.getHotels().stream()
                .filter(o -> o != null && o.getName() != null && !o.getName().isBlank() && o.getPricePerNight() > 0)
                .map(o -> Hotel.builder()
                        .name(o.getName().trim())
                        .city(city)
                        .starRating(Math.max(2, Math.min(5, o.getStarRating())))
                        .pricePerNight(BigDecimal.valueOf(Math.round(o.getPricePerNight())))
                        .amenities(o.getAmenities() != null ? o.getAmenities() : List.of())
                        .build())
                .toList();
    }

    private Hotel resolveRecommended(String recommendedHotelName, List<Hotel> hotels) {
        if (recommendedHotelName != null && !recommendedHotelName.isBlank()) {
            String name = recommendedHotelName.trim();
            var matched = hotels.stream()
                    .filter(h -> h.getName().equals(name))
                    .findFirst();
            if (matched.isPresent()) {
                return matched.get();
            }
            log.warn("LLM 推荐酒店 [{}] 不在候选列表中，回退最低价酒店", name);
        }
        return hotels.stream()
                .min(Comparator.comparing(Hotel::getPricePerNight))
                .orElse(hotels.getFirst());
    }
}
