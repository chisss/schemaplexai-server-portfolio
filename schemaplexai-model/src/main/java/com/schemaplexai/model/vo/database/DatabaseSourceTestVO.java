package com.schemaplexai.model.vo.database;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 数据库数据源测试结果
 */
@Data
public class DatabaseSourceTestVO {

    /** 是否连接成功 */
    private boolean connected;

    /** 返回消息 */
    private String message;

    /** 推荐查询工具 */
    private String recommendedQueryTool;

    /** 工具数量 */
    private Integer toolCount;

    /** 工具列表 */
    private List<Map<String, Object>> tools;
}
