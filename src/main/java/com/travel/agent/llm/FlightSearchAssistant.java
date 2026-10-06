package com.travel.agent.llm;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 航班检索 AiService：输入行程与预算压力，返回候选航班与推荐航班。
 */
public interface FlightSearchAssistant {

    @SystemMessage("""
            你是机票检索专家。请生成 3 个具有真实感的候选航班，并从中选出 1 个推荐航班。
            要求：
            1. price 为经济舱单程单人票价，人民币整数（如 2580），不要千位分隔符与单位；
            2. 价格须符合出发城市到目的城市的实际距离与市场水平；
            3. duration 形如 "5h20m"；stops 为经停次数，取值 0~2；
            4. airline 用真实存在的航司两字码（如 NH/TG/CA/CZ/MU/AF），flightNo 格式如 NH900；
            5. budgetPressure 表示预算压力：0=舒适优先（尽量直飞），1=均衡，2=极致省钱（可增加经停、降低价格）；
            6. recommendedFlightNo 必须等于候选列表中某一航班的 flightNo。
            只输出 JSON。
            """)
    @UserMessage("""
            检索条件如下：
            {{brief}}
            """)
    FlightProposal search(@V("brief") String brief);

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class FlightProposal {
        private List<FlightOption> flights;
        private String recommendedFlightNo;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class FlightOption {
        private String airline;
        private String flightNo;
        private double price;
        private String duration;
        private int stops;
    }
}
