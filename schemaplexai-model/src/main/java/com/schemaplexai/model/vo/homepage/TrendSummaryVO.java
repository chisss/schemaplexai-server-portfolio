package com.schemaplexai.model.vo.homepage;

import lombok.Data;

import java.util.List;

/**
 * 异常趋势摘要
 */
@Data
public class TrendSummaryVO {

    /** 最近 7 天每天的异常数 */
    private List<Integer> dailyCounts;

    /** 日期标签 */
    private List<String> labels;

    /** 趋势方向：up / down / stable */
    private String trend;

    private int totalCount;
}
