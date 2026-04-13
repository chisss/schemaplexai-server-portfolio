package com.schemaplexai.model.vo.database;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 数据库查询结果
 */
@Data
public class DatabaseQueryResultVO {

    /** 实际执行的工具名 */
    private String toolName;

    /** 列 */
    private List<String> columns;

    /** 行 */
    private List<Map<String, Object>> rows;

    /** 行数 */
    private Integer rowCount;

    /** 是否截断 */
    private Boolean truncated;

    /** 耗时 */
    private Long elapsedMs;

    /** 原始载荷 */
    private Map<String, Object> rawPayload;

    /** 原始文本 */
    private String rawText;

    /** 警告 */
    private List<String> warnings;
}
