package com.travel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.entity.PlanRecord;
import com.travel.mapper.PlanRecordMapper;
import com.travel.model.BudgetBreakdown;
import com.travel.model.PlanningState;
import com.travel.model.TravelPlanState;
import com.travel.model.UserPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 规划记录服务：MySQL 业务数据的唯一写入/查询口。
 * <p>
 * 设计要点：落库失败只告警、不阻断规划响应——数据库故障时用户仍能拿到完整规划结果，
 * 记录缺失可由日志对账补录（面向可用性的取舍）。
 * </p>
 */
@Service
public class PlanRecordService {

    private static final Logger log = LoggerFactory.getLogger(PlanRecordService.class);

    private final PlanRecordMapper mapper;
    private final ObjectMapper objectMapper;

    public PlanRecordService(PlanRecordMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /** 规划完成后落库；返回记录 ID，失败返回 null（不影响响应） */
    public Long save(String sessionId, String source, UserPreferences preferences, TravelPlanState state) {
        try {
            BudgetBreakdown bd = state.getBudgetBreakdown();
            PlanRecord record = PlanRecord.builder()
                    .sessionId(sessionId != null && !sessionId.isBlank() ? sessionId.trim() : "anonymous")
                    .source(source)
                    .inputJson(objectMapper.writeValueAsString(preferences))
                    .destination(state.getSelectedDestination() != null ? state.getSelectedDestination().getCity() : null)
                    .totalCost(bd != null ? bd.getTotal() : null)
                    .withinBudget(bd != null && bd.isWithinBudget() ? 1 : 0)
                    .adjustmentRound(state.getAdjustmentRound())
                    .status(state.getPlanningState().name())
                    .resultJson(objectMapper.writeValueAsString(state))
                    .errorMessage(state.getErrorMessage())
                    .createdAt(LocalDateTime.now())
                    .build();
            mapper.insert(record);
            return record.getId();
        } catch (Exception e) {
            log.warn("规划记录落库失败（不影响本次规划响应）: {}", e.getMessage());
            return null;
        }
    }

    /** 会话内历史（列表用，剔除大字段 resultJson） */
    public List<PlanRecord> listBySession(String sessionId) {
        try {
            List<PlanRecord> records = mapper.selectList(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<PlanRecord>lambdaQuery()
                            .eq(PlanRecord::getSessionId, sessionId)
                            .orderByDesc(PlanRecord::getId));
            records.forEach(r -> r.setResultJson(null));
            return records;
        } catch (Exception e) {
            log.warn("规划历史查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 全部会话的历史（行程规划页聚合展示用，剔除大字段 resultJson），按 id 倒序 */
    public List<PlanRecord> listAll() {
        try {
            List<PlanRecord> records = mapper.selectList(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<PlanRecord>lambdaQuery()
                            .orderByDesc(PlanRecord::getId));
            records.forEach(r -> r.setResultJson(null));
            return records;
        } catch (Exception e) {
            log.warn("规划历史全量查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 单条详情（含完整 resultJson） */
    public PlanRecord getById(Long id) {
        try {
            return mapper.selectById(id);
        } catch (Exception e) {
            log.warn("规划详情查询失败: {}", e.getMessage());
            return null;
        }
    }

    /** 删除规划记录（MySQL 行删除，含完整结果 JSON）；返回是否删除成功 */
    public boolean deleteById(Long id) {
        try {
            return mapper.deleteById(id) > 0;
        } catch (Exception e) {
            log.warn("规划记录删除失败: {}", e.getMessage());
            return false;
        }
    }
}
