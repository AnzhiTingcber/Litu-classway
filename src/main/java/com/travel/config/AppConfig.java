package com.travel.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 应用级配置。
 * <p>
 * 为 {@link com.travel.orchestrator.ParallelExecutor} 提供专用线程池，
 * 避免在并行阶段使用 {@link java.util.concurrent.ForkJoinPool#commonPool()} 与业务无关任务抢资源。
 * 面试点：线程池隔离、命名线程便于排查、队列容量与拒绝策略在生产环境需再评估。
 * </p>
 */
@Configuration
public class AppConfig {

    @Bean(name = "travelPlanningExecutor")
    public Executor travelPlanningExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("travel-plan-");
        executor.initialize();
        return executor;
    }

    /**
     * OpenAPI 文档元信息（Knife4j /doc.html 顶部展示）。
     */
    @Bean
    public OpenAPI travelPlannerOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("多智能体旅行行程规划 API")
                .version("1.0.0")
                .description("Pipeline 串行前置 + CompletableFuture 并行检索 + 预算反馈循环；LLM 驱动（LangChain4j / 智谱 GLM）。"
                        + "注意：POST /api/plan 会真实调用 LLM，单次约 40~120 秒，超预算自动降级重试（最多 3 轮，耗时相应增加）。"));
    }
}
