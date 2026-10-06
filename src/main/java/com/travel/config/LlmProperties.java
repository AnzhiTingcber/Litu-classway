package com.travel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 连接配置（OpenAI 兼容协议）。
 * <p>
 * 通过环境变量注入：{@code LLM_API_KEY}（必填）、{@code LLM_BASE_URL}、{@code LLM_MODEL_NAME}；
 * 把 base-url 换成 DeepSeek / 阿里云百炼 / 智谱 / Ollama 等兼容端点即可切换厂商，代码无需改动。
 * </p>
 */
@Data
@ConfigurationProperties(prefix = "app.llm")
public class LlmProperties {

    /** API Key（必填），对应环境变量 LLM_API_KEY */
    private String apiKey;

    /** OpenAI 兼容端点地址 */
    private String baseUrl = "https://api.openai.com/v1";

    /** 模型名称 */
    private String modelName = "gpt-4o-mini";

    private double temperature = 0.7;

    /** 单次 LLM 调用超时（秒）。并行阶段三个 Agent 同时调用，端到端耗时约等于最慢的一次 */
    private int timeoutSeconds = 90;

    /** 传输层自动重试次数 */
    private int maxRetries = 2;

    private boolean logRequests = false;
    private boolean logResponses = false;
}
