package com.schemaplexai.model.vo.monitor;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 通用报表数据VO
 */
@Data
public class ReportDataVO {
    private List<String> columns;
    private List<List<Object>> rows;
    private Map<String, Object> summary;
    private List<Map<String, Object>> trend;
}
