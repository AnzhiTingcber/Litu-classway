package com.travel.controller;

import com.travel.config.RagProperties;
import com.travel.rag.CityKnowledgeBase;
import com.travel.rag.CityKnowledgeBase.CityDoc;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 城市知识库只读接口：把 RAG 知识库的运营数据暴露给管理端页面。
 */
@Tag(name = "02 知识库", description = "城市知识库（RAG）只读查询")
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final CityKnowledgeBase knowledgeBase;
    private final RagProperties ragProperties;

    public KnowledgeController(CityKnowledgeBase knowledgeBase, RagProperties ragProperties) {
        this.knowledgeBase = knowledgeBase;
        this.ragProperties = ragProperties;
    }

    @Operation(summary = "知识库城市列表", description = "返回已收录城市的结构化知识与向量检索启用状态")
    @GetMapping("/cities")
    public Map<String, Object> cities() {
        List<Map<String, Object>> cities = knowledgeBase.allCities().stream()
                .map(this::toMap)
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ragEnabled", knowledgeBase.isVectorSearchAvailable());
        result.put("embeddingModel", ragProperties.getEmbeddingModelName());
        result.put("embeddingBaseUrl", ragProperties.getEmbeddingBaseUrl());
        result.put("total", cities.size());
        result.put("cities", cities);
        return result;
    }

    private Map<String, Object> toMap(CityDoc d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("city", d.city());
        m.put("province", d.province());
        m.put("positioning", d.positioning());
        m.put("seasons", d.seasons());
        m.put("suggestedDays", d.suggestedDays());
        m.put("consumption", d.consumption());
        m.put("safetyScore", d.safetyScore());
        m.put("styleTags", d.styleTags());
        m.put("highlights", d.highlights());
        m.put("activities", d.activities());
        return m;
    }
}
