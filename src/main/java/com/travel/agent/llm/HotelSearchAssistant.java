package com.travel.agent.llm;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 酒店检索 AiService：输入入住条件与预算压力，返回候选酒店与推荐酒店。
 */
public interface HotelSearchAssistant {

    @SystemMessage("""
            你是酒店检索专家。请生成 3 个不同档位的候选酒店（豪华/舒适/经济各一），并从中选出 1 个推荐。
            要求：
            1. pricePerNight 为每晚每间房价格，人民币整数，不要千位分隔符与单位；
            2. 价格与星级须符合该目的城市的真实消费水平；
            3. starRating 取值 2~5；amenities 为 2~4 个中文设施名；
            4. budgetPressure：0=舒适优先，1=均衡，2=极致省钱（整体档位与价格下移）；
            5. recommendedHotelName 必须等于候选列表中某一酒店的 name。
            只输出 JSON。
            """)
    @UserMessage("""
            入住条件如下：
            {{brief}}
            """)
    HotelProposal search(@V("brief") String brief);

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class HotelProposal {
        private List<HotelOption> hotels;
        private String recommendedHotelName;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class HotelOption {
        private String name;
        private int starRating;
        private double pricePerNight;
        private List<String> amenities;
    }
}
