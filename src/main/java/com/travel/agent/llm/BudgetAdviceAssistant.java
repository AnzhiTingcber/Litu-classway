package com.travel.agent.llm;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 预算建议 AiService：输入费用构成与超支情况，返回可执行的调整建议。
 */
public interface BudgetAdviceAssistant {

    @SystemMessage("""
            你是预算优化顾问。用户行程总费用超出预算，请给出 2~3 条具体、可执行的中文调整建议，
            每条不超过 40 字，须结合超支金额与费用构成给出量化方向（如优先降哪一项、大约可省多少）。
            只输出 JSON。
            """)
    @UserMessage("""
            费用与预算情况如下：
            {{brief}}
            """)
    BudgetAdvice advise(@V("brief") String brief);

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class BudgetAdvice {
        private List<String> suggestions;
    }
}
