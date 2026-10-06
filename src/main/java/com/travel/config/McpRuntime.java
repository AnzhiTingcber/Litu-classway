package com.travel.config;

import dev.langchain4j.mcp.client.McpClient;

import java.util.List;

/**
 * 已连接的 MCP 客户端集合（仅在 app.mcp.enabled=true 时作为 Bean 存在）。
 * 供 MemoryConfig 把外部工具并入对话助手，SystemController 输出状态摘要。
 * <p>
 * 实现 {@link AutoCloseable}：Spring 关闭应用时自动逐个 close 客户端，
 * 显式终止 stdio 子进程（正常情况下子进程在管道 EOF 后会自行退出，这里是双保险）。
 * </p>
 */
public class McpRuntime implements AutoCloseable {

    private final List<String> serverNames;
    private final List<McpClient> clients;

    public McpRuntime(List<String> serverNames, List<McpClient> clients) {
        this.serverNames = List.copyOf(serverNames);
        this.clients = List.copyOf(clients);
    }

    /** 是否有至少一个可用的 MCP server */
    public boolean isEnabled() {
        return !clients.isEmpty();
    }

    public List<String> getServerNames() {
        return serverNames;
    }

    public List<McpClient> getClients() {
        return clients;
    }

    @Override
    public void close() {
        for (McpClient client : clients) {
            try {
                client.close();
            } catch (Exception ignored) {
                // 单个客户端关闭失败不影响其他，进程退出兜底靠管道 EOF
            }
        }
    }
}
