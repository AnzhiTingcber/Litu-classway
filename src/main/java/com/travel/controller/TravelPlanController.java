package com.travel.controller;

import com.travel.model.PlanningState;
import com.travel.model.TravelPlanState;
import com.travel.model.UserPreferences;
import com.travel.service.TravelPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST 控制器：健康检查 + 规划接口（表单入口）。
 */
@Tag(name = "01 旅行规划", description = "多智能体行程规划：偏好校验 → 目的地甄选 → 航班/酒店/活动并行检索 → 预算闭环")
@RestController
@RequestMapping("/api")
public class TravelPlanController {

    private final TravelPlanService travelPlanService;

    public TravelPlanController(TravelPlanService travelPlanService) {
        this.travelPlanService = travelPlanService;
    }

    @Operation(summary = "健康检查", description = "服务存活探测，无 LLM 调用")
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "travel-planner");
    }

    @Operation(summary = "生成行程规划（表单入口）",
            description = "执行完整多智能体流水线（调用真实 LLM），单次约 40~150 秒。"
                    + "请求头 X-Session-Id 可选：携带后规划记录与会话聚合（行程规划历史页可见）。"
                    + "返回码：200 成功；400 输入校验失败（validationFailure=true）；502 规划过程/LLM 调用失败。")
    @PostMapping("/plan")
    public ResponseEntity<TravelPlanState> plan(@RequestBody UserPreferences preferences,
                                                @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        TravelPlanState state = travelPlanService.plan(preferences, sessionId, "form");
        if (state.getPlanningState() == PlanningState.FAILED) {
            // 输入校验失败 → 400；规划过程 / LLM 调用失败 → 502（上游依赖故障）
            return state.isValidationFailure()
                    ? ResponseEntity.status(HttpStatus.BAD_REQUEST).body(state)
                    : ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(state);
        }
        return ResponseEntity.ok(state);
    }
}
