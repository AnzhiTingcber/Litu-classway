package com.travel.config;

import com.travel.agent.TravelChatAssistant;
import com.travel.store.MongoChatMemoryStore;
import com.travel.tools.PlanningTools;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 会话记忆装配：MemoryProvider 按 sessionId 构建窗口记忆，落 Mongo 持久化。
 * MCP 外部工具（app.mcp.enabled=true）经 McpToolProvider 并入同一工具链。
 */
@Configuration
public class MemoryConfig {

    @Bean
    public ChatMemoryProvider chatMemoryProvider(MongoChatMemoryStore store) {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(100)
                .chatMemoryStore(store)
                .build();
    }

    @Bean
    public TravelChatAssistant travelChatAssistant(StreamingChatModel streamingChatModel,
                                                   ChatMemoryProvider chatMemoryProvider,
                                                   PlanningTools planningTools,
                                                   ObjectProvider<McpRuntime> mcpRuntime) {
        AiServices<TravelChatAssistant> builder = AiServices.builder(TravelChatAssistant.class)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .tools(planningTools);
        // MCP 外部工具与本地 @Tool 并列：模型按需调用，单个 server 失败不拖垮其他工具
        McpRuntime mcp = mcpRuntime.getIfAvailable();
        if (mcp != null && mcp.isEnabled()) {
            builder.toolProvider(McpToolProvider.builder()
                    .mcpClients(mcp.getClients())
                    .failIfOneServerFails(false)
                    .build());
        }
        return builder.build();
    }
}
