package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.RagOperationLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * RAG 操作日志 Mapper
 */
@Mapper
public interface RagOperationLogMapper extends BaseMapper<RagOperationLog> {

    /**
     * 按租户与上下文精确查询日志。
     *
     * <p>除直接 context_id 外，还会命中 metadata 中记录的 contextIds / matchedContextIds，
     * 用于回收 Agent 多上下文检索产生的 query 日志。
     */
    @Select({
            "<script>",
            "SELECT *",
            "FROM sf_rag_operation_log",
            "WHERE tenant_id = CAST(#{tenantId} AS uuid)",
            "<if test=\"sourceType != null and sourceType != ''\">",
            "  AND source_type = #{sourceType}",
            "</if>",
            "<if test=\"contextId != null and contextId != ''\">",
            "  AND (",
            "       context_id = CAST(#{contextId} AS uuid)",
            "       OR jsonb_exists(COALESCE(metadata -&gt; 'contextIds', '[]'::jsonb), #{contextId})",
            "       OR jsonb_exists(COALESCE(metadata -&gt; 'matchedContextIds', '[]'::jsonb), #{contextId})",
            "  )",
            "</if>",
            "ORDER BY created_at DESC",
            "LIMIT #{limit}",
            "</script>"
    })
    List<RagOperationLog> selectRecentByTenantAndContext(@Param("tenantId") String tenantId,
                                                         @Param("contextId") String contextId,
                                                         @Param("sourceType") String sourceType,
                                                         @Param("limit") int limit);
}
