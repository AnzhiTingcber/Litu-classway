package com.travel.model;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 执行轨迹收集器：挂在 {@link TravelPlanState} 上随流水线走。
 * <p>
 * 事件双路分发：<b>全量累积</b>（随规划结果序列化落库，历史可回放）+
 * <b>实时转发</b>给监听者（对话入口借此把事件推到 SSE）。
 * 并行阶段三个 Agent 线程并发写入，因此用并发容器；监听者异常被吞掉，不影响规划主流程。
 * </p>
 */
public class TraceCollector {

    private final List<TraceEvent> events = new CopyOnWriteArrayList<>();
    private final List<Consumer<TraceEvent>> listeners = new CopyOnWriteArrayList<>();

    public void add(TraceEvent event) {
        events.add(event);
        for (Consumer<TraceEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception ignored) {
                // 轨迹推送失败不影响规划（如 SSE 客户端已断开）
            }
        }
    }

    /** 注册实时监听者（对话入口注册后事件即流入 SSE） */
    public void addListener(Consumer<TraceEvent> listener) {
        listeners.add(listener);
    }

    /** 已累积的全部事件（时间正序的不可变快照），Jackson 经此序列化 */
    public List<TraceEvent> getEvents() {
        return List.copyOf(events);
    }
}
