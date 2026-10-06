package com.travel.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户偏好（API 入参核心模型）。
 * <p>
 * 设计模式：DTO + Builder（Lombok），便于 JSON 反序列化与测试构造；
 * 日期使用 {@code yyyy-MM-dd} 与前端约定一致。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "旅行规划需求（POST /api/plan 请求体）")
public class UserPreferences {

    @Schema(description = "总预算（正数，抽象金额口径）", example = "15000", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal budget;

    @Schema(description = "旅行风格（缺省默认 RELAXED）", example = "CULTURE",
            allowableValues = {"RELAXED", "ADVENTURE", "CULTURE", "LUXURY", "BUDGET_FRIENDLY"})
    private TravelStyle style;

    @Schema(description = "出发日期", example = "2026-05-01", requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    @Schema(description = "返回日期（必须晚于出发日期）", example = "2026-05-05", requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    @Schema(description = "出发城市", example = "上海", requiredMode = Schema.RequiredMode.REQUIRED)
    private String departureCity;

    @Schema(description = "出行人数（至少 1 人）", example = "2", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private int travelers;

    @Schema(description = "兴趣标签（可空，缺省按旅行风格自动补全）", example = "[\"美食\",\"博物馆\"]")
    @Builder.Default
    private List<String> interests = new ArrayList<>();
}
