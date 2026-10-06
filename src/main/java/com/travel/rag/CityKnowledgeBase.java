package com.travel.rag;

import com.travel.agent.BaseAgent;
import com.travel.config.RagProperties;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.ClassPathDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 中国城市知识库：RAG 的知识供给方，服务目的地选择与活动编排。
 * <p>
 * 设计要点（面试可讲）：
 * <ul>
 *     <li><b>知识可运营</b>：城市内容全部来自 {@code resources/knowledge/*.md}，
 *     新增/修改城市只改文档、不发版；</li>
 *     <li><b>双层供给</b>：结构化字段（亮点/活动/消费水平）直接喂给 Agent 提示词，
 *     全文向量块用于语义检索排序；</li>
 *     <li><b>惰性容错入库</b>：首次使用时向量化入库；Embedding 服务不可用不影响启动，
     *  语义检索自动退化为「全量候选」，本地解析路径零外部依赖；</li>
 *     <li>文档格式约定：首行 {@code # 城市名}，二级标题「基本信息/亮点/经典活动与体验」。</li>
 * </ul>
 * </p>
 */
@Service
public class CityKnowledgeBase {

    public static final String CITY_METADATA = "city";
    private static final String KNOWLEDGE_DIR = "knowledge";

    private static final Logger log = LoggerFactory.getLogger(CityKnowledgeBase.class);

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final RagProperties props;

    private final Map<String, CityDoc> citiesByName = new LinkedHashMap<>();
    private volatile boolean ingestAttempted = false;
    private volatile boolean vectorSearchAvailable = false;

    public CityKnowledgeBase(EmbeddingModel embeddingModel,
                             EmbeddingStore<TextSegment> embeddingStore,
                             RagProperties props) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.props = props;
    }

    /**
     * 一次解析后的城市文档（结构化字段 + 原文，本地路径，零外部依赖）。
     */
    public record CityDoc(String city, String province, String positioning, String seasons,
                          String suggestedDays, String consumption, double safetyScore, String styleTags,
                          List<String> highlights, List<String> activities, String rawText) {
    }

    /**
     * 按语义相关性返回候选城市（Top-K 去重）；Embedding 不可用时退回全量候选。
     */
    public List<CityDoc> relevantCities(String query) {
        ensureIngested();
        List<CityDoc> all = allCities();
        if (!vectorSearchAvailable || query == null || query.isBlank() || all.isEmpty()) {
            return all;
        }
        try {
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            EmbeddingSearchResult<TextSegment> result = embeddingStore.search(EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(props.getMaxResults() * 3)
                    .minScore(props.getMinScore())
                    .build());
            LinkedHashMap<String, CityDoc> ranked = new LinkedHashMap<>();
            for (EmbeddingMatch<TextSegment> match : result.matches()) {
                String city = match.embedded().metadata().getString(CITY_METADATA);
                CityDoc doc = city != null ? citiesByName.get(city) : null;
                if (doc != null) {
                    ranked.putIfAbsent(doc.city(), doc);
                }
                if (ranked.size() >= props.getMaxResults()) {
                    break;
                }
            }
            return ranked.isEmpty() ? all : new ArrayList<>(ranked.values());
        } catch (Exception e) {
            log.warn("城市知识库语义检索失败，本次退回全量候选: {}", BaseAgent.rootMessage(e));
            return all;
        }
    }

    /** 取指定城市文档；未收录返回 null。 */
    public CityDoc city(String name) {
        ensureIngested();
        return name != null ? citiesByName.get(name.trim()) : null;
    }

    public List<CityDoc> allCities() {
        ensureIngested();
        return new ArrayList<>(citiesByName.values());
    }

    /** 语义检索是否可用（入库成功且未降级），供管理端观测页展示。 */
    public boolean isVectorSearchAvailable() {
        ensureIngested();
        return vectorSearchAvailable;
    }

    /**
     * 惰性入库：类路径加载 → 解析 → 切块 → 向量化 → 清空重灌。
     * <p>
     * 防碎片重复的三重机制：
     * <ol>
     *     <li><b>确定性 ID</b>：分块 ID = uuid(城市#块序号)（MD5 派生，内容不变则 ID 不变），
     *     Pinecone 按 ID 覆盖写入，重复入库零新增向量；</li>
     *     <li><b>先向量化后写库</b>：Embedding 失败（欠费/断网）时中止于写库之前，
     *     云端存量数据保持完整，服务退化为全量候选；</li>
     *     <li><b>清空重灌</b>：写入前 removeAll() 清空（travel-cities 索引专用于本知识库），
     *     文档收缩后遗留的过期分块一并清除，杜绝"新文档 + 旧碎片"并存。</li>
     * </ol>
     * 任何失败只禁用语义检索，不阻断服务（本地解析路径始终可用）。
     * </p>
     */
    private synchronized void ensureIngested() {
        if (ingestAttempted) {
            return;
        }
        ingestAttempted = true;
        try {
            List<Document> documents = ClassPathDocumentLoader.loadDocuments(KNOWLEDGE_DIR, new TextDocumentParser());
            if (documents.isEmpty()) {
                throw new IllegalStateException("类路径 " + KNOWLEDGE_DIR + " 下没有知识文档");
            }
            DocumentSplitter splitter = DocumentSplitters.recursive(400, 40);
            List<TextSegment> segments = new ArrayList<>();
            List<String> segmentIds = new ArrayList<>();
            for (Document document : documents) {
                CityDoc doc = parse(document.text());
                citiesByName.put(doc.city(), doc);
                document.metadata().put(CITY_METADATA, doc.city());
                List<TextSegment> chunks = splitter.split(document);
                for (int i = 0; i < chunks.size(); i++) {
                    segments.add(chunks.get(i));
                    segmentIds.add(deterministicId(doc.city(), i));
                }
            }
            // 先向量化：失败则在此中止，不触碰向量库中的存量数据
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
            // 清空重灌：确定性 ID + 全量覆盖，幂等且无过期碎片
            embeddingStore.removeAll();
            embeddingStore.addAll(segmentIds, embeddings, segments);
            vectorSearchAvailable = true;
            log.info("城市知识库向量入库完成：{} 个城市 / {} 个分块（确定性 ID 覆盖），向量库={}，语义检索已启用",
                    citiesByName.size(), segments.size(), embeddingStore.getClass().getSimpleName());
        } catch (Exception e) {
            log.warn("城市知识库向量入库失败，语义检索退化为全量候选: {}", BaseAgent.rootMessage(e));
        }
    }

    /** 分块的确定性 ID：同一城市同一块序号永远得到同一 ID（MD5 派生 UUID），重复入库即覆盖 */
    private static String deterministicId(String city, int chunkIndex) {
        return UUID.nameUUIDFromBytes((city + "#" + chunkIndex).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * 按固定格式解析 Markdown：首行 {@code # 城市}，「基本信息」键值行、「亮点」与「经典活动与体验」列表。
     */
    private CityDoc parse(String text) {
        String city = null;
        String section = "";
        Map<String, String> info = new LinkedHashMap<>();
        List<String> highlights = new ArrayList<>();
        List<String> activities = new ArrayList<>();

        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("# ") && city == null) {
                city = line.substring(2).trim();
            } else if (line.startsWith("## ")) {
                section = line.substring(3).trim();
            } else if (line.startsWith("- ")) {
                String item = line.substring(2).trim();
                if ("亮点".equals(section)) {
                    highlights.add(item);
                } else if (section.contains("经典活动")) {
                    activities.add(item);
                }
            } else if (line.contains("：") && "基本信息".equals(section)) {
                int idx = line.indexOf('：');
                info.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
            }
        }
        if (city == null || city.isBlank()) {
            throw new IllegalStateException("知识文档缺少首行「# 城市名」标题");
        }
        double safety = info.entrySet().stream()
                .filter(e -> e.getKey().contains("安全指数"))
                .map(Map.Entry::getValue)
                .findFirst()
                .map(v -> {
                    try {
                        return Double.parseDouble(v);
                    } catch (NumberFormatException e) {
                        return 8.0;
                    }
                })
                .orElse(8.0);
        return new CityDoc(
                city,
                info.entrySet().stream().filter(e -> e.getKey().contains("省份"))
                        .map(Map.Entry::getValue).findFirst().orElse("中国"),
                info.entrySet().stream().filter(e -> e.getKey().contains("城市定位"))
                        .map(Map.Entry::getValue).findFirst().orElse(""),
                info.entrySet().stream().filter(e -> e.getKey().contains("最佳季节"))
                        .map(Map.Entry::getValue).findFirst().orElse(""),
                info.entrySet().stream().filter(e -> e.getKey().contains("建议天数"))
                        .map(Map.Entry::getValue).findFirst().orElse(""),
                info.entrySet().stream().filter(e -> e.getKey().contains("消费水平"))
                        .map(Map.Entry::getValue).findFirst().orElse(""),
                safety,
                info.entrySet().stream().filter(e -> e.getKey().contains("适合风格"))
                        .map(Map.Entry::getValue).findFirst().orElse(""),
                highlights,
                activities,
                text);
    }

    @SuppressWarnings("unused")
    private static List<String> splitList(String value) {
        return value == null ? List.of() : Arrays.asList(value.split("[、,，]"));
    }
}
