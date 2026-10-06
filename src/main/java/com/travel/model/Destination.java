package com.travel.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 目的地候选或选定结果。
 * <p>
 * 面试点：领域模型与「检索结果」分离；{@code safety_score} 用于 DestinationAgent 的 mock 打分演示。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Destination {

    private String city;
    private String country;
    private String description;
    private List<String> highlights;
    /** 0~10 安全分，mock 数据 */
    private double safetyScore;
}
