package com.travel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP 客户端配置（app.mcp.*）：对话助理经 {@code McpToolProvider} 调用外部 MCP server 的工具。
 * <p>
 * 默认关闭（enabled=false，不改变现有启动路径）；开启后按列表逐个连接，
 * 单个 server 连不上只告警跳过，不影响应用启动与其他 server。
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.mcp")
public class McpProperties {

    /** 是否启用 MCP 客户端（启用后外部工具并入对话助手的工具链） */
    private boolean enabled = false;

    /** 外部 MCP server 列表 */
    private List<Server> servers = new ArrayList<>();

    @Data
    public static class Server {
        /** server 名称（日志与状态页展示） */
        private String name;
        /** 传输类型：stdio（宿主拉起子进程）或 http（Streamable HTTP/SSE 远程端点） */
        private String type = "stdio";
        /** stdio：启动命令及参数，如 [npx, -y, @modelcontextprotocol/server-everything] */
        private List<String> command;
        /** stdio：子进程环境变量（可空） */
        private Map<String, String> env;
        /** http：Streamable HTTP 端点地址 */
        private String url;
    }
}
