package com.travel.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * MySQL 建表初始化：应用启动时确保 plan_record 表存在（幂等）。
 * 测试环境通过 app.schema-init.enabled=false 关闭，保持用例密闭。
 */
@Component
public class PlanSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final boolean enabled;

    public PlanSchemaInitializer(JdbcTemplate jdbcTemplate,
                                 @Value("${app.schema-init.enabled:true}") boolean enabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS plan_record (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        session_id VARCHAR(64) NOT NULL,
                        source VARCHAR(16) NOT NULL DEFAULT 'form',
                        input_json TEXT,
                        destination VARCHAR(128),
                        total_cost DECIMAL(12,2),
                        within_budget TINYINT,
                        adjustment_round INT,
                        status VARCHAR(32),
                        result_json LONGTEXT,
                        error_message VARCHAR(512),
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        INDEX idx_plan_session (session_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
        } catch (Exception e) {
            // 建表失败不阻断启动：规划功能可用，落库与历史在 DB 恢复后自动生效
        }
    }
}
