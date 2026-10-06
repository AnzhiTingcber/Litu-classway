package com.travel.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 酒店检索结果容器。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HotelSearchResult {

    private List<Hotel> hotels;
    private Hotel recommended;
    /** 入住晚数 × 每晚单价（可含简易税费 mock） */
    private BigDecimal totalCost;
}
