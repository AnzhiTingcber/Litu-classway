package com.travel.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.llm.DestinationSelectionAssistant;
import com.travel.agent.llm.DestinationSelectionAssistant.DestinationChoice;
import com.travel.model.Destination;
import com.travel.model.PlanningState;
import com.travel.model.TraceEvent;
import com.travel.model.TravelPlanState;
import com.travel.model.TravelStyle;
import com.travel.model.UserPreferences;
import com.travel.rag.CityKnowledgeBase;
import com.travel.rag.CityKnowledgeBase.CityDoc;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 目的地智能体：RAG 检索候选城市 → LLM 在候选内甄选 → 白名单校验。
 * <p>
 * 设计要点（面试可讲）：
 * <ul>
 *     <li><b>知识来自向量库</b>：候选城市由 CityKnowledgeBase 按用户兴趣/风格语义检索得出，
 *     扩城市只需加 Markdown 文档，不改代码；</li>
 *     <li><b>检索即白名单</b>：LLM 只允许在检索命中的城市中选择，返回城市名经匹配校验，
 *     胡诌目录外的城市会被拦截；</li>
 *     <li><b>双保险兜底</b>：LLM 调用失败或返回非法城市时，退回「兴趣关键词命中计数」的
 *     确定性打分（候选文档本地解析，不依赖任何外部服务），目的地阶段永不阻塞流水线。</li>
 * </ul>
 * </p>
 */
@Component
public class DestinationAgent extends BaseAgent {

    /** 风格 → 中文标签（用于检索查询与关键词兜底打分） */
    private static final Map<TravelStyle, String> STYLE_CN = Map.of(
            TravelStyle.CULTURE, "文化历史",
            TravelStyle.RELAXED, "休闲",
            TravelStyle.ADVENTURE, "户外探险",
            TravelStyle.LUXURY, "奢华",
            TravelStyle.BUDGET_FRIENDLY, "高性价比");

    private final DestinationSelectionAssistant assistant;
    private final CityKnowledgeBase knowledgeBase;
    private final ObjectMapper objectMapper;

    public DestinationAgent(DestinationSelectionAssistant assistant,
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
        if (pref == null) {
            fail(state, "缺少用户偏好");
            return;
        }

        String query = buildQuery(pref);
        List<CityDoc> candidates = knowledgeBase.relevantCities(query);
        if (candidates.isEmpty()) {
            fail(state, "城市知识库为空，无法推荐目的地");
            return;
        }
        log.info("RAG 检索候选城市（{} 个）：{}", candidates.size(),
                candidates.stream().map(CityDoc::city).toList());
        if (state.getTrace() != null) {
            // 检索子步骤：语义检索命中情况（Embedding 不可用时为全量候选降级）
            state.getTrace().add(new TraceEvent(System.currentTimeMillis(),
                    "DestinationAgent#r" + state.getAdjustmentRound() + "#rag", TraceEvent.TYPE_AGENT,
                    "RAG 城市知识库检索",
                    (knowledgeBase.isVectorSearchAvailable() ? "语义检索 Top-" + candidates.size() : "Embedding 不可用，降级全量候选")
                            + "：" + candidates.stream().map(CityDoc::city).toList().toString(),
                    TraceEvent.DONE, 0, state.getAdjustmentRound()));
        }

        try {
            List<Map<String, Object>> candidateBrief = candidates.stream().map(d -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("城市", d.city());
                m.put("定位", d.positioning());
                m.put("最佳季节", d.seasons());
                m.put("建议天数", d.suggestedDays());
                m.put("消费水平", d.consumption());
                m.put("适合风格", d.styleTags());
                m.put("亮点", d.highlights());
                m.put("代表体验", d.activities().stream().limit(3).toList());
                return m;
            }).toList();
            Map<String, Object> brief = new LinkedHashMap<>();
            brief.put("候选城市知识库", candidateBrief);
            brief.put("用户画像", pref);

            DestinationChoice choice = assistant.choose(objectMapper.writeValueAsString(brief));
            CityDoc match = matchCity(candidates, choice.getCity());
            if (match != null) {
                apply(state, match, choice.getReason());
                return;
            }
            log.warn("LLM 返回城市 [{}] 不在 RAG 候选中，回退关键词打分", choice.getCity());
        } catch (Exception e) {
            log.warn("目的地选择 LLM 调用失败，回退关键词打分: {}", rootMessage(e));
        }

        apply(state, keywordFallback(candidates, pref), "语义检索/LLM 不可用，按兴趣关键词匹配回退");
    }

    @Override
    protected String displayName() {
        return "DestinationAgent · 目的地甄选";
    }

    @Override
    protected String runningDetail(TravelPlanState state) {
        return "RAG 语义检索候选城市 → LLM 在白名单内选择";
    }

    @Override
    protected String doneDetail(TravelPlanState state) {
        Destination dest = state.getSelectedDestination();
        if (dest == null) {
            return "完成";
        }
        String reason = state.getSelectionReason();
        return "目的地「" + dest.getCity() + "」" + (reason != null && !reason.isBlank() ? "— " + reason : "");
    }

    private String buildQuery(UserPreferences pref) {
        List<String> parts = new ArrayList<>();
        parts.add(STYLE_CN.getOrDefault(pref.getStyle(), "旅行"));
        if (pref.getInterests() != null) {
            pref.getInterests().stream().filter(i -> i != null && !i.isBlank()).forEach(parts::add);
        }
        return String.join(" ", parts);
    }

    /** 白名单校验：先精确匹配，再做包含匹配（容忍 LLM 带出省份等前后缀） */
    private CityDoc matchCity(List<CityDoc> candidates, String city) {
        if (city == null || city.isBlank()) {
            return null;
        }
        String normalized = city.trim();
        return candidates.stream()
                .filter(d -> d.city().equals(normalized))
                .findFirst()
                .or(() -> candidates.stream()
                        .filter(d -> d.city().contains(normalized) || normalized.contains(d.city()))
                        .findFirst())
                .orElse(null);
    }

    /** 确定性兜底：兴趣关键词在候选城市文档中的命中计数（+安全分微调） */
    private CityDoc keywordFallback(List<CityDoc> candidates, UserPreferences pref) {
        String styleCn = STYLE_CN.getOrDefault(pref.getStyle(), "");
        CityDoc best = null;
        double bestScore = -1;
        for (CityDoc doc : candidates) {
            double score = 0;
            if (pref.getInterests() != null) {
                for (String interest : pref.getInterests()) {
                    if (interest != null && !interest.isBlank() && doc.rawText().contains(interest.trim())) {
                        score += 2;
                    }
                }
            }
            if (!styleCn.isEmpty() && doc.rawText().contains(styleCn)) {
                score += 1;
            }
            score += doc.safetyScore() / 10;
            if (score > bestScore) {
                bestScore = score;
                best = doc;
            }
        }
        return best != null ? best : candidates.getFirst();
    }

    private void apply(TravelPlanState state, CityDoc doc, String reason) {
        Destination destination = Destination.builder()
                .city(doc.city())
                .country(doc.province().isBlank() ? "中国" : doc.province())
                .description(doc.positioning())
                .highlights(doc.highlights())
                .safetyScore(doc.safetyScore())
                .build();
        state.setSelectedDestination(destination);
        state.setSelectionReason(reason);
        state.setPlanningState(PlanningState.DESTINATION_SELECTED);
        log.info("推荐目的地: {}，理由: {}", doc.city(), reason);
    }
}
