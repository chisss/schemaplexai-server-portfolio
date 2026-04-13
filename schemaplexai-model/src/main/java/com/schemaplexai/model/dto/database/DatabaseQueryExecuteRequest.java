package com.schemaplexai.model.dto.database;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 数据库查询执行请求
 */
@Data
public class DatabaseQueryExecuteRequest {

    /** SQL */
    @NotBlank(message = "SQL不能为空")
    private String sql;

    /** 结果行数上限 */
    private Integer limit = 200;
}
