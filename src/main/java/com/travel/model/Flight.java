package com.travel.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 航班信息（mock）。
 * <p>
 * 价格通常为单人单程示意；总费用在 {@link FlightSearchResult} 中按人数/往返规则汇总。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Flight {

    private String airline;
    private String flightNo;
    private BigDecimal price;
    /** 如 "6h30m" */
    private String duration;
    private int stops;
}
