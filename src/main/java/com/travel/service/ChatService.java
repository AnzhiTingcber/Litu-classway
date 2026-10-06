package com.travel.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.TravelChatAssistant;
import com.travel.config.McpRuntime;
import com.travel.model.TraceEvent;
import com.travel.store.MongoChatMemoryStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * 对话服务：会话隔离的 SSE 流式入口。
 * sessionId 经 @MemoryId 注入对话与工具（框架透传，线程无关），落库自然带会话标识。
 */
@Service
public class ChatService {

    private final TravelChatAssistant assistant;
    private final MongoChatMemoryStore memoryStore;
    private final TracePublisher tracePublisher;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<McpRuntime> mcpRuntime;

    public ChatService(TravelChatAssistant assistant, MongoChatMemoryStore memoryStore,
                       TracePublisher tracePublisher, ObjectMapper objectMapper,
                       ObjectProvider<McpRuntime> mcpRuntime) {
        this.assistant = assistant;
        this.memoryStore = memoryStore;
        this.tracePublisher = tracePublisher;
        this.objectMapper = objectMapper;
        this.mcpRuntime = mcpRuntime;
    }

    /**
     * 流式对话：结构化 SSE（命名事件）——
     * <ul>
     *     <li>{@code token}：回复正文，逐 token 推送（Markdown）;</li>
     *     <li>{@code trace}：执行轨迹事件 JSON（多智能体步骤实时点亮，见 {@link TraceEvent}）;</li>
     *     <li>{@code done}：流结束哨兵。</li>
     * </ul>
     * 会话记忆照常写 MongoDB；当前日期注入提示词（LLM 无时间感知）。
     * <p>
     * 实现说明：不用 mergeWith 挂接轨迹流——轨迹源在无事件时永不 complete，
     * 会拖死 merge 使 done 永不发出；这里手动订阅 token 流、注册轨迹消费者共用同一
     * emitter，token 流完成时主动发 done 并收流（客户端断开时反向取消 LLM 订阅）。
     * </p>
     */
    public Flux<ServerSentEvent<String>> chatStream(String sessionId, String message) {
        String sid = sessionId.trim();
        return Flux.create(emitter -> {
            Runnable unregisterTrace = tracePublisher.register(sid,
                    event -> emitter.next(ServerSentEvent.<String>builder(toJson(event)).event("trace").build()));
            Disposable tokenSubscription = assistant.chat(sid, message, currentDate()).subscribe(
                    token -> emitter.next(ServerSentEvent.<String>builder(token).event("token").build()),
                    emitter::error,
                    () -> {
                        emitter.next(ServerSentEvent.<String>builder("").event("done").build());
                        emitter.complete();
                    });
            emitter.onDispose(() -> {
                unregisterTrace.run();
                tokenSubscription.dispose();
            });
        });
    }

    private String toJson(TraceEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    /** 形如 "2026-10-05 星期一" + MCP 能力声明，供 LLM 换算相对日期并知道能否查实时天气 */
    private String currentDate() {
        java.time.LocalDate today = java.time.LocalDate.now();
        String[] weeks = {"一", "二", "三", "四", "五", "六", "日"};
        String weekday = "星期" + weeks[today.getDayOfWeek().getValue() - 1];
        // 常用相对日期速查表：LLM 的星期心算不可靠，给出确定性锚点
        String text = "今天 " + today + "（" + weekday + "）\n"
                + "明天 " + today.plusDays(1) + "\n"
                + "后天 " + today.plusDays(2) + "\n"
                + "本周六 " + today.plusDays(6 - today.getDayOfWeek().getValue()) + "\n"
                + "本周日 " + today.plusDays(7 - today.getDayOfWeek().getValue()) + "\n"
                + "下周一 " + today.plusDays(8 - today.getDayOfWeek().getValue()) + "\n"
                + "下周五 " + today.plusDays(12 - today.getDayOfWeek().getValue()) + "\n"
                + "下周日 " + today.plusDays(14 - today.getDayOfWeek().getValue());
        // 动态能力注入：只在 MCP 真的接入时声明，避免关闭状态下模型凭空编天气
        McpRuntime mcp = mcpRuntime.getIfAvailable();
        if (mcp != null && mcp.isEnabled()) {
            text += "\nMCP 外部工具已接入（" + String.join("、", mcp.getServerNames())
                    + "）：目的地天气等实时信息请直接调用对应工具查询后回答";
        }
        return text;
    }

    /** 会话消息历史（供前端刷新后恢复对话），按时间正序 */
    public List<Map<String, Object>> history(String sessionId) {
        return memoryStore.getMessages(sessionId).stream()
                .map(this::toView)
                .collect(java.util.stream.Collectors.toList());
    }

    public void clearSession(String sessionId) {
        memoryStore.deleteMessages(sessionId);
    }

    /** 全部会话摘要（侧边栏），按最近活跃倒序 */
    public List<MongoChatMemoryStore.SessionSummary> listSessions() {
        return memoryStore.listSessions();
    }

    private Map<String, Object> toView(ChatMessage message) {
        Map<String, Object> view = new LinkedHashMap<>();
        if (message instanceof UserMessage um) {
            view.put("role", "user");
            view.put("content", um.singleText());
        } else if (message instanceof AiMessage am) {
            view.put("role", "assistant");
            view.put("content", am.text() != null ? am.text() : "");
        } else {
            view.put("role", "system");
            view.put("content", "");
        }
        return view;
    }
}
