package com.travel.service;

import com.travel.model.TraceEvent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 实时轨迹发布器：sessionId → SSE sink 的注册表。
 * <p>
 * 规划流水线在工具执行线程里运行（ThreadLocal 不可靠，见 PlanningTools 的历史坑），
 * 因此以 sessionId 为键做跨线程投递：对话入口经 {@link #register} 注册消费者，
 * 流水线各环节经 {@link TraceCollector} 监听者或直接调用 {@link #publish} 推送事件。
 * 无订阅者或会话不存在时发布是空操作，表单入口零开销。
 * </p>
 */
@Component
public class TracePublisher {

    private final Map<String, List<Consumer<TraceEvent>>> sinks = new ConcurrentHashMap<>();

    /**
     * 为某会话注册一个实时轨迹消费者，返回注销函数（SSE 断开/完成时调用）。
     * 消费者回调里直接向 SSE emitter 推事件；与 token 流共用同一 emitter，
     * 由 ChatService 控制完成时机，避免「trace 流永不 complete 拖死 mergeWith」的问题。
     */
    public Runnable register(String sessionId, Consumer<TraceEvent> listener) {
        List<Consumer<TraceEvent>> list =
                sinks.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
        list.add(listener);
        return () -> {
            list.remove(listener);
            sinks.computeIfPresent(sessionId, (k, v) -> v.isEmpty() ? null : v);
        };
    }

    /** 向会话的所有订阅者推送事件；无订阅者时忽略 */
    public void publish(String sessionId, TraceEvent event) {
        if (sessionId == null || event == null) {
            return;
        }
        List<Consumer<TraceEvent>> list = sinks.get(sessionId);
        if (list != null) {
            list.forEach(listener -> {
                try {
                    listener.accept(event);
                } catch (Exception ignored) {
                    // 单个订阅者异常（如客户端已断开）不影响其他订阅者与规划主流程
                }
            });
        }
    }
}
