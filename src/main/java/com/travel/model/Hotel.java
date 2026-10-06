package com.travel.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 酒店信息（mock）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Hotel {

    private String name;
    private String city;
    private int starRating;
    private BigDecimal pricePerNight;
    private List<String> amenities;
}
