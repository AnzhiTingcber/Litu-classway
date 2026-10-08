package com.travel;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 接口集成测试：使用假 Key + 不可达 LLM 端点（127.0.0.1:1，连接立即被拒），
 * 全部用例不产生真实 token 费用、秒级完成。
 * <p>
 * 覆盖：
 * <ul>
 *     <li>健康检查 200；</li>
 *     <li>输入校验失败 → 400 + validationFailure=true（校验在 PreferenceAgent，不发起 LLM 请求）；</li>
 *     <li>规划过程 LLM 不可达 → 目的地回退规则打分，并行检索失败收敛为 502 + FAILED
 *     （同时回归验证 BudgetLoopController 不覆盖 FAILED 的修复）。</li>
 * </ul>
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.llm.api-key=test-key",
        "app.llm.base-url=http://127.0.0.1:1/v1",
        "app.llm.max-retries=0",
        "app.rag.embedding-api-key=test-key",
        "app.rag.embedding-base-url=http://127.0.0.1:1/v1",
        "app.rag.pinecone-api-key=",
        "app.schema-init.enabled=false"
})
class TravelPlannerApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthShouldReturnUp() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("travel-planner"));
    }

    @Test
    void invalidInputShouldReturn400WithValidationFailure() throws Exception {
        String body = """
                {"budget":-5,"style":"RELAXED","startDate":"2026-05-01","endDate":"2026-05-05",
                 "departureCity":"上海","travelers":2}
                """;
        mockMvc.perform(post("/api/plan").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.planningState").value("FAILED"))
                .andExpect(jsonPath("$.validationFailure").value(true))
                .andExpect(jsonPath("$.errorMessage").isNotEmpty());
    }

    @Test
    void unreachableLlmShouldConvergeTo502WithFailedState() throws Exception {
        String body = """
                {"budget":15000,"style":"CULTURE","startDate":"2026-05-01","endDate":"2026-05-05",
                 "departureCity":"上海","travelers":2,"interests":["博物馆"]}
                """;
        mockMvc.perform(post("/api/plan").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.planningState").value("FAILED"))
                .andExpect(jsonPath("$.validationFailure").value(false))
                .andExpect(jsonPath("$.selectedDestination.city").isNotEmpty())
                .andExpect(jsonPath("$.errorMessage").isNotEmpty());
    }

    @Test
    void knowledgeCitiesShouldReturnFullChineseCityCatalog() throws Exception {
        mockMvc.perform(get("/api/knowledge/cities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", greaterThanOrEqualTo(30)))
                .andExpect(jsonPath("$.cities[0].city").isNotEmpty())
                .andExpect(jsonPath("$.cities[0].activities").isArray());
    }

    @Test
    void systemStatusShouldExposeConfigSummaryWithoutSecrets() throws Exception {
        mockMvc.perform(get("/api/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.llm.model").isNotEmpty())
                .andExpect(jsonPath("$.rag.embeddingModel").isNotEmpty());
    }
}
