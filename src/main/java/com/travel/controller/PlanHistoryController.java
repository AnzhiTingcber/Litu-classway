package com.travel.controller;

import com.travel.entity.PlanRecord;
import com.travel.service.PlanRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 规划历史（MySQL 业务数据查询）：表单与对话两个入口的记录统一在此聚合。
 */
@Tag(name = "05 规划历史", description = "会话内的规划记录（MySQL 持久化，表单/对话统一聚合）")
@RestController
@RequestMapping("/api/plans")
public class PlanHistoryController {

    private final PlanRecordService planRecordService;

    public PlanHistoryController(PlanRecordService planRecordService) {
        this.planRecordService = planRecordService;
    }

    @Operation(summary = "规划记录列表", description = "带 sessionId 按会话过滤；不带参数返回全部会话的记录（行程规划页聚合展示）。新记录在前，不含大字段 resultJson")
    @GetMapping
    public Map<String, Object> list(@RequestParam(value = "sessionId", required = false) String sessionId) {
        List<PlanRecord> records = sessionId != null && !sessionId.isBlank()
                ? planRecordService.listBySession(sessionId.trim())
                : planRecordService.listAll();
        return Map.of("total", records.size(), "records", records);
    }

    @Operation(summary = "规划详情", description = "含完整 TravelPlanState（行程单/预算测算/执行信息）")
    @GetMapping("/{id}")
    public ResponseEntity<PlanRecord> detail(@PathVariable Long id) {
        PlanRecord record = planRecordService.getById(id);
        return record != null ? ResponseEntity.ok(record) : ResponseEntity.notFound().build();
    }

    @Operation(summary = "删除规划记录", description = "删除 MySQL 中对应的行程单记录（含完整结果 JSON），不可恢复")
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        boolean deleted = planRecordService.deleteById(id);
        return deleted
                ? ResponseEntity.ok(Map.of("deleted", true, "id", id))
                : ResponseEntity.notFound().build();
    }
}
