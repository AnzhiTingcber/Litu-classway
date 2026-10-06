package com.travel.controller;

import com.travel.config.LlmProperties;
import com.travel.config.McpRuntime;
import com.travel.config.RagProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统状态只读接口：管理端「运行与观测」页的数据源。
 * <p>
 * 只暴露端点与模型名等非敏感摘要，绝不返回 API Key。
 * </p>
 */
@Tag(name = "03 系统状态", description = "运行与观测：LLM / RAG 配置摘要（不含密钥）")
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final LlmProperties llmProperties;
    private final RagProperties ragProperties;
    private final ObjectProvider<McpRuntime> mcpRuntime;

    public SystemController(LlmProperties llmProperties, RagProperties ragProperties,
                            ObjectProvider<McpRuntime> mcpRuntime) {
        this.llmProperties = llmProperties;
        this.ragProperties = ragProperties;
        this.mcpRuntime = mcpRuntime;
    }

    @Operation(summary = "系统状态摘要", description = "健康状态 + LLM/RAG 配置摘要（不含任何密钥）+ MCP 外部工具连接状态")
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("baseUrl", llmProperties.getBaseUrl());
        llm.put("model", llmProperties.getModelName());
        llm.put("timeoutSeconds", llmProperties.getTimeoutSeconds());

        Map<String, Object> rag = new LinkedHashMap<>();
        rag.put("embeddingBaseUrl", ragProperties.getEmbeddingBaseUrl());
        rag.put("embeddingModel", ragProperties.getEmbeddingModelName());

        McpRuntime mcp = mcpRuntime.getIfAvailable();
        Map<String, Object> mcpView = new LinkedHashMap<>();
        mcpView.put("enabled", mcp != null && mcp.isEnabled());
        mcpView.put("servers", mcp != null ? mcp.getServerNames() : List.of());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("llm", llm);
        result.put("rag", rag);
        result.put("mcp", mcpView);
        result.put("timestamp", LocalDateTime.now().toString());
        return result;
    }
}
