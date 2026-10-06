package com.travel.store;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * MongoDB 持久化会话记忆（复用 xiaozhi 项目的 MongoChatMemoryStore 模式）。
 * <p>
 * 面试点：实现 LangChain4j 的 {@link ChatMemoryStore} 三方法即可接入任意存储；
 * memoryId = sessionId，天然按会话隔离；MessageWindowChatMemory 负责窗口裁剪，
 * 本类只管存取，不关心记忆策略。
 * </p>
 */
@Component
public class MongoChatMemoryStore implements ChatMemoryStore {

    private static final String COLLECTION = "chat_memory";

    private final MongoTemplate mongoTemplate;

    public MongoChatMemoryStore(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        ChatMemoryDocument doc = mongoTemplate.findById(memoryId.toString(), ChatMemoryDocument.class, COLLECTION);
        if (doc == null || doc.getMessages() == null) {
            return new ArrayList<>();
        }
        return doc.getMessages().stream().map(ChatMessageDeserializer::messageFromJson).toList();
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        List<String> json = messages.stream().map(ChatMessageSerializer::messageToJson).toList();
        ChatMemoryDocument doc = new ChatMemoryDocument(memoryId.toString(), json, java.time.LocalDateTime.now());
        mongoTemplate.save(doc, COLLECTION);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        mongoTemplate.remove(Query.query(Criteria.where("_id").is(memoryId.toString())), ChatMemoryDocument.class, COLLECTION);
    }

    /** 会话摘要（侧边栏列表用）：按最近活跃倒序，标题取第一条用户消息。 */
    public List<SessionSummary> listSessions() {
        return mongoTemplate.findAll(ChatMemoryDocument.class, COLLECTION).stream()
                .map(d -> {
                    String title = "（无用户消息）";
                    int count = 0;
                    try {
                        List<ChatMessage> msgs = d.getMessages() == null ? List.of()
                                : d.getMessages().stream().map(ChatMessageDeserializer::messageFromJson).toList();
                        count = msgs.size();
                        title = msgs.stream()
                                .filter(m -> m instanceof UserMessage)
                                .map(m -> ((UserMessage) m).singleText())
                                .findFirst()
                                .map(t -> t.length() > 22 ? t.substring(0, 22) + "…" : t)
                                .orElse(title);
                    } catch (Exception ignored) {
                        // 单条消息反序列化异常不影响列表展示
                    }
                    return new SessionSummary(d.getId(), title, count, d.getUpdatedAt());
                })
                .sorted(java.util.Comparator.comparing(SessionSummary::updatedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .toList();
    }

    /** 会话列表条目 */
    public record SessionSummary(String sessionId, String title, int messageCount, java.time.LocalDateTime updatedAt) {
    }
}
