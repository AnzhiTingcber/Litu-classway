package com.travel.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 单项活动（mock）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Activity {

    private String name;
    private String category;
    private BigDecimal price;
    /** 如 "2小时" */
    private String duration;
    /** 如 "上午" / "14:00-16:00" */
    private String timeSlot;
}
