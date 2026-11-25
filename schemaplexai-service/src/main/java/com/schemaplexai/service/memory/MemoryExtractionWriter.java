package com.schemaplexai.service.memory;

import com.schemaplexai.dao.mapper.ChatMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.function.ToIntFunction;

/** 使用同一事务提交提取标记和提取结果。 */
@Component
@RequiredArgsConstructor
public class MemoryExtractionWriter {

    private final ChatMessageMapper chatMessageMapper;

    @Transactional
    public <T> List<T> writeOnce(String executionId, Extractor extractor, List<T> results,
                                 ToIntFunction<T> insert) {
        if (results.isEmpty() || chatMessageMapper.claimMemoryExtraction(executionId, extractor.name()) == 0) {
            return List.of();
        }
        for (T result : results) {
            if (insert.applyAsInt(result) != 1) {
                throw new IllegalStateException("持久化长期记忆提取结果失败");
            }
        }
        return results;
    }

    public enum Extractor {
        AGENT, USER
    }
}
