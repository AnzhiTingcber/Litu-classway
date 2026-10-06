package com.travel.agent.llm;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 目的地选择 AiService：输入 RAG 检索出的候选城市知识库 + 用户画像，返回结构化选择结果。
 */
public interface DestinationSelectionAssistant {

    @SystemMessage("""
            你是旅行目的地推荐专家。你只能从「候选城市知识库」中选择一个城市，
            禁止推荐候选之外的城市。城市字段必须与候选中的城市名完全一致。
            reason 需引用该城市知识库中的事实（亮点、最佳季节或消费水平之一），
            用一句不超过 40 字的中文说明推荐理由。只输出 JSON。
            """)
    @UserMessage("""
            候选城市知识库与用户画像如下：
            {{brief}}
            """)
    DestinationChoice choose(@V("brief") String brief);

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class DestinationChoice {
        private String city;
        private String reason;
    }
}
