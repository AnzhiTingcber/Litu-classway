package com.travel.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 航班检索结果容器。
 * <p>
 * 推荐项 + 列表：便于 API 直接展示「首选」与「备选」。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlightSearchResult {

    private List<Flight> flights;
    private Flight recommended;
    /** 本次规划计入预算的航班总成本（mock：往返 × 人数） */
    private BigDecimal totalCost;
}
