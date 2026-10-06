package com.travel.controller;

import com.travel.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * 对话式规划入口：标准 SSE 流式输出（text/event-stream），会话记忆持久化在 MongoDB。
 */
@Tag(name = "04 对话规划", description = "带会话记忆的对话式行程规划（SSE 流式，LLM 决策何时触发规划流水线）")
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    public record ChatForm(String sessionId, String message) {
    }

    @Operation(summary = "发送对话消息（SSE 流式，含执行轨迹）",
            description = "标准 text/event-stream，命名事件：event=token（回复正文，逐 token Markdown）、"
                    + "event=trace（执行轨迹事件 JSON，多智能体步骤实时点亮）、event=done（流结束）。"
                    + "同一 sessionId 的多轮消息共享记忆（MongoDB 持久化）；信息齐全时助理会自动触发规划流水线"
                    + "（工具执行阶段 40~150 秒无 token，但会持续推送 trace 事件）。")
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@RequestBody ChatForm form) {
        if (form == null || form.sessionId() == null || form.sessionId().isBlank()
                || form.message() == null || form.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId 与 message 均不能为空");
        }
        return chatService.chatStream(form.sessionId().trim(), form.message().trim());
    }

    @Operation(summary = "会话列表", description = "全部会话摘要（按最近活跃倒序），供对话页侧边栏展示")
    @GetMapping("/sessions")
    public Map<String, Object> sessions() {
        return Map.of("sessions", chatService.listSessions());
    }

    @Operation(summary = "会话消息历史", description = "从 MongoDB 读取该会话全部消息（刷新页面后恢复对话）")
    @GetMapping("/{sessionId}/messages")
    public Map<String, Object> history(@PathVariable String sessionId) {
        return Map.of("sessionId", sessionId, "messages", chatService.history(sessionId));
    }

    @Operation(summary = "清空会话记忆", description = "删除该会话在 MongoDB 中的全部消息")
    @DeleteMapping("/{sessionId}")
    public Map<String, Object> clear(@PathVariable String sessionId) {
        chatService.clearSession(sessionId);
        return Map.of("sessionId", sessionId, "cleared", true);
    }
}
