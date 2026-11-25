package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.ChatMessageEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 对话消息 Mapper。 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessageEntity> {

    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(#{conversationId}, 734329))")
    int lockConversation(@Param("conversationId") String conversationId);

    @Select("""
            SELECT COALESCE(MAX(message_index), -1) + 1 FROM sf_chat_message
            WHERE conversation_id = #{conversationId}
            """)
    int nextMessageIndex(@Param("conversationId") String conversationId);

    @Select("""
            SELECT COALESCE(MAX(turn_index), 0) + 1 FROM sf_chat_message
            WHERE conversation_id = #{conversationId}
            """)
    int nextTurnIndex(@Param("conversationId") String conversationId);

    @Insert("""
            INSERT INTO sf_chat_memory_extraction (execution_id, extractor)
            VALUES (#{executionId}, #{extractor})
            ON CONFLICT (execution_id, extractor) DO NOTHING
            """)
    int claimMemoryExtraction(@Param("executionId") String executionId, @Param("extractor") String extractor);
}
