package com.travel.model;

/**
 * 旅行风格枚举。
 * <p>
 * 设计说明：与 {@link com.travel.agent.PreferenceAgent} 的默认兴趣映射配合，
 * 体现「领域词汇表」与业务规则分离，便于扩展新风格而不改核心流水线。
 * </p>
 */
public enum TravelStyle {
    /** 休闲度假，偏慢节奏与舒适体验 */
    RELAXED,
    /** 探险户外，偏体力与刺激项目 */
    ADVENTURE,
    /** 人文历史，偏博物馆、古迹 */
    CULTURE,
    /** 轻奢享受，偏高端酒店与精品体验 */
    LUXURY,
    /** 精打细算，优先性价比 */
    BUDGET_FRIENDLY
}
