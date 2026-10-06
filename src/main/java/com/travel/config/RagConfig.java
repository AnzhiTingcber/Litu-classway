package com.travel.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.pinecone.PineconeEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 基础设施：向量化模型 + 向量库。
 * <p>
 * 向量库按配置自动选择：配置了 {@code app.rag.pinecone-api-key} 时用 Pinecone 持久化
 * （跨重启共享，天然免重复入库），留空则退回内存库（重启重建，零依赖）。
 * 二者实现同一 {@link EmbeddingStore} 接口，Agent 代码无感知——接口就是切换的缝隙。
 * </p>
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    @Bean
    public EmbeddingModel embeddingModel(RagProperties props) {
        return OpenAiEmbeddingModel.builder()
                .apiKey(props.getEmbeddingApiKey())
                .baseUrl(props.getEmbeddingBaseUrl())
                .modelName(props.getEmbeddingModelName())
                .build();
    }

    @Bean
    public EmbeddingStore<TextSegment> cityEmbeddingStore(RagProperties props) {
        if (props.getPineconeApiKey() == null || props.getPineconeApiKey().isBlank()) {
            log.info("未配置 Pinecone，城市知识库使用内存向量库（重启后重建）");
            return new InMemoryEmbeddingStore<>();
        }
        log.info("城市知识库使用 Pinecone 持久化向量库，索引: {}", props.getPineconeIndex());
        return PineconeEmbeddingStore.builder()
                .apiKey(props.getPineconeApiKey())
                .index(props.getPineconeIndex())
                .build();
    }
}
