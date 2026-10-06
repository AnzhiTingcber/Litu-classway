package com.travel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 城市知识库配置。
 * <p>
 * 向量化默认走 SiliconFlow 的 BAAI/bge-m3（与 xiaozhi 项目同源），
 * 任意 OpenAI 兼容 embedding 端点均可通过环境变量覆盖（如智谱 embedding-3）。
 * </p>
 */
@Data
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {

    /** Embedding API 端点（OpenAI 兼容） */
    private String embeddingBaseUrl = "https://api.siliconflow.cn/v1";

    /** Embedding API Key，对应环境变量 RAG_EMBEDDING_API_KEY */
    private String embeddingApiKey;

    /** 向量化模型名 */
    private String embeddingModelName = "BAAI/bge-m3";

    /** 目的地语义检索返回的候选城市数上限 */
    private int maxResults = 6;

    /** 相似度分数阈值（0~1，低于该值的分块不采纳） */
    private double minScore = 0.4;

    /** Pinecone API Key（留空则退回内存向量库），对应环境变量 PINECONE_API_KEY */
    private String pineconeApiKey;

    /** Pinecone 索引名，索引维度必须与 Embedding 模型一致（bge-m3 = 1024） */
    private String pineconeIndex = "travel-cities";
}
