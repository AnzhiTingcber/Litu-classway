package com.travel.store;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * MongoDB 会话记忆文档：一个 sessionId 一份，消息以 LangChain4j JSON 序列化串存储。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "chat_memory")
public class ChatMemoryDocument {

    /** 即 sessionId（memoryId） */
    @Id
    private String id;

    /** ChatMessageSerializer 序列化后的消息列表 */
    private List<String> messages = new ArrayList<>();

    /** 最近一次消息更新时间（会话列表排序用） */
    private LocalDateTime updatedAt;
}
