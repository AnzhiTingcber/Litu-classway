package com.travel.agent.llm;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 活动规划 AiService：输入行程日期与兴趣，返回逐日活动安排。
 */
public interface ActivityPlanAssistant {

    @SystemMessage("""
            你是行程活动规划师。请为给定日期列表中的每一天安排 2~4 个活动。
            要求：
            1. 严格逐天输出，date 使用 yyyy-MM-dd 且与输入日期完全一致，不得增减或改动日期；
            2. timeSlot 只能取：上午 / 下午 / 晚间；duration 用中文（如 "2小时"）；
            3. price 为每人费用，人民币整数，免费活动填 0，不要千位分隔符；
            4. 活动内容必须优先从「目的地知识库」列出的真实景点与体验中选取并合理分配到各天，
               价格参考知识库标注；知识库为空时可自行设计贴合该城市的通用活动；
            5. category 用中文短词（如 观光/门票/美食/购物/户外/博物馆）；
            6. budgetPressure：0=可包含付费景点/演出/购物，1=适度控制花费，2=尽量安排免费或低价活动。
            只输出 JSON。
            """)
    @UserMessage("""
            规划条件如下：
            {{brief}}
            """)
    ActivityPlan plan(@V("brief") String brief);

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ActivityPlan {
        private List<DayActivities> days;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class DayActivities {
        private String date;
        private List<ActivityOption> activities;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ActivityOption {
        private String name;
        private String category;
        private double price;
        private String duration;
        private String timeSlot;
    }
}
