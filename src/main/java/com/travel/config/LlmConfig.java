package com.travel.config;

import com.travel.agent.llm.ActivityPlanAssistant;
import com.travel.agent.llm.BudgetAdviceAssistant;
import com.travel.agent.llm.DestinationSelectionAssistant;
import com.travel.agent.llm.FlightSearchAssistant;
import com.travel.agent.llm.HotelSearchAssistant;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LLM 基础设施配置：构建 {@link ChatModel} 与各 Agent 的 AiServices 接口代理。
 * <p>
 * 面试点：LangChain4j 的 AiServices 通过动态代理，把「接口 + 注解提示词模板 + POJO 返回类型」
 * 一次 LLM 调用封装为普通方法调用（自动追加 JSON 输出约束，并把响应反序列化为类型安全对象）。
 * {@link ChatModel} 实现与 AiServices 代理均线程安全，可被并行阶段的多个 Agent 共享。
 * </p>
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {

    @Bean
    public ChatModel chatModel(LlmProperties props) {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw new IllegalStateException(
                    "未配置 LLM API Key：请先设置环境变量 LLM_API_KEY（可选 LLM_BASE_URL / LLM_MODEL_NAME）再启动，"
                            + "Windows 示例：set LLM_API_KEY=sk-xxx");
        }
        return OpenAiChatModel.builder()
                .apiKey(props.getApiKey())
                .baseUrl(props.getBaseUrl())
                .modelName(props.getModelName())
                .temperature(props.getTemperature())
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .maxRetries(props.getMaxRetries())
                .logRequests(props.isLogRequests())
                .logResponses(props.isLogResponses())
                .build();
    }

    /** 流式模型：与同步模型同配置，专供对话入口（SSE）逐 token 输出 */
    @Bean
    public StreamingChatModel streamingChatModel(LlmProperties props) {
        return OpenAiStreamingChatModel.builder()
                .apiKey(props.getApiKey())
                .baseUrl(props.getBaseUrl())
                .modelName(props.getModelName())
                .temperature(props.getTemperature())
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .logRequests(props.isLogRequests())
                .build();
    }

    @Bean
    public DestinationSelectionAssistant destinationSelectionAssistant(ChatModel chatModel) {
        return AiServices.create(DestinationSelectionAssistant.class, chatModel);
    }

    @Bean
    public FlightSearchAssistant flightSearchAssistant(ChatModel chatModel) {
        return AiServices.create(FlightSearchAssistant.class, chatModel);
    }

    @Bean
    public HotelSearchAssistant hotelSearchAssistant(ChatModel chatModel) {
        return AiServices.create(HotelSearchAssistant.class, chatModel);
    }

    @Bean
    public ActivityPlanAssistant activityPlanAssistant(ChatModel chatModel) {
        return AiServices.create(ActivityPlanAssistant.class, chatModel);
    }

    @Bean
    public BudgetAdviceAssistant budgetAdviceAssistant(ChatModel chatModel) {
        return AiServices.create(BudgetAdviceAssistant.class, chatModel);
    }
}
