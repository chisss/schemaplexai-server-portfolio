package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.AgentTraceSpan;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Agent Trace Span Mapper
 */
@Mapper
public interface AgentTraceSpanMapper extends BaseMapper<AgentTraceSpan> {

    @Insert("""
            INSERT INTO sf_agent_trace_span (
                tenant_id, created_by, updated_by, trace_id, span_id, parent_span_id, agent_id, span_type, name,
                started_at, duration_ms, input_tokens, output_tokens, cost, status, failure_category, failure_reason,
                recoverable, model_attempt
            ) VALUES (
                CAST(#{et.tenantId,jdbcType=VARCHAR} AS uuid), CAST(#{et.createdBy,jdbcType=VARCHAR} AS uuid),
                CAST(#{et.updatedBy,jdbcType=VARCHAR} AS uuid), #{et.traceId}, #{et.spanId}, #{et.parentSpanId},
                #{et.agentId}, #{et.spanType}, #{et.name}, #{et.startedAt}, #{et.durationMs}, #{et.inputTokens},
                #{et.outputTokens}, #{et.cost,jdbcType=NUMERIC}, #{et.status}, #{et.failureCategory,jdbcType=VARCHAR},
                #{et.failureReason,jdbcType=VARCHAR}, #{et.recoverable,jdbcType=BOOLEAN},
                CAST(#{et.modelAttempt,typeHandler=com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler} AS jsonb)
            ) ON CONFLICT (span_id) WHERE model_attempt IS NOT NULL DO NOTHING
            """)
    int insertModelAttempt(@Param("et") AgentTraceSpan span);
}
