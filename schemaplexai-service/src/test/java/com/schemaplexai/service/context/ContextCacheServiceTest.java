package com.schemaplexai.service.context;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextCacheServiceTest {

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);

    private final ContextCacheService service = new ContextCacheService(redisTemplate);

    @SuppressWarnings("unchecked")
    @Test
    void shouldUseExecutionScopedTeamKeyWhenExecutionIdPresent() {
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hash);

        service.updateTeamSharedContext("team-1", "exec-1", "sub-1", "summary");

        verify(hash).put("sf:ctx:team:team-1:exec-1:shared", "sub-1", "summary");
        verify(redisTemplate).expire("sf:ctx:team:team-1:exec-1:shared", Duration.ofMinutes(10));
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldFallBackToLegacyTeamKeyWithoutExecutionId() {
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hash);

        service.updateTeamSharedContext("team-1", "sub-1", "summary");

        verify(hash).put("sf:ctx:team:team-1:shared", "sub-1", "summary");
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldReadExecutionScopedTeamContext() {
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hash);
        when(hash.entries("sf:ctx:team:team-1:exec-1:shared")).thenReturn(Map.of("sub-1", "summary"));

        Map<String, String> result = service.getTeamSharedContext("team-1", "exec-1");

        assertThat(result).containsEntry("sub-1", "summary");
    }

    @Test
    void shouldEvictBothKeysWhenExecutionIdPresent() {
        service.evictTeamSharedContext("team-1", "exec-1");

        verify(redisTemplate).delete("sf:ctx:team:team-1:exec-1:shared");
        verify(redisTemplate).delete("sf:ctx:team:team-1:shared");
    }
}
