package com.travel.config;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP 客户端装配：按 app.mcp.servers 逐个连接外部 MCP server。
 * <p>
 * 设计要点：
 * <ul>
 *     <li>启动期握手探活（listTools）：连不上的 server 告警跳过，不阻断应用启动；</li>
 *     <li>Windows 兼容：ProcessBuilder 无法直接执行 npx/npm 这类 .cmd 脚本，自动包一层 cmd /c；</li>
 *     <li>stdio / streamable http 双传输，与 Claude Desktop 等宿主常用配置语义对齐。</li>
 * </ul>
 * </p>
 */
@Configuration
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true")
public class McpConfig {

    private static final Logger log = LoggerFactory.getLogger(McpConfig.class);

    @Bean
    public McpRuntime mcpRuntime(McpProperties props) {
        List<McpClient> clients = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (McpProperties.Server server : props.getServers()) {
            if (server.getName() == null || server.getName().isBlank()) {
                continue;
            }
            try {
                McpTransport transport = buildTransport(server);
                McpClient client = new DefaultMcpClient.Builder()
                        .key(server.getName())
                        .transport(transport)
                        // stdio 首次拉起（如 npx 冷启动下载包）可能较慢，放宽握手超时
                        .initializationTimeout(Duration.ofSeconds(60))
                        .protocolDetectionTimeout(Duration.ofSeconds(60))
                        .toolExecutionTimeout(Duration.ofSeconds(60))
                        .build();
                client.listTools(); // 握手 + 工具发现探活；失败则本 server 不并入
                clients.add(client);
                names.add(server.getName());
                log.info("MCP server 已连接: {} ({}), 工具 {} 个",
                        server.getName(), server.getType(), client.listTools().size());
            } catch (Exception e) {
                log.warn("MCP server [{}] 连接失败，跳过: {}", server.getName(), rootMessage(e));
            }
        }
        return new McpRuntime(names, clients);
    }

    private McpTransport buildTransport(McpProperties.Server server) {
        if ("http".equalsIgnoreCase(server.getType()) || "sse".equalsIgnoreCase(server.getType())) {
            return new StreamableHttpMcpTransport.Builder()
                    .url(server.getUrl())
                    .timeout(Duration.ofSeconds(30))
                    .build();
        }
        List<String> command = new ArrayList<>(server.getCommand());
        if (isWindows() && !command.isEmpty()) {
            String exe = command.get(0);
            if (exe.equalsIgnoreCase("npx") || exe.equalsIgnoreCase("npm")) {
                // Windows 下 npx/npm 实为 .cmd 脚本，ProcessBuilder 需经 cmd /c 拉起
                command.add(0, "/c");
                command.add(0, "cmd.exe");
            }
        }
        return new StdioMcpTransport.Builder()
                .command(command)
                .environment(server.getEnv() != null ? server.getEnv() : Map.of())
                .logEvents(true)
                .build();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String rootMessage(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
