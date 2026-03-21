package com.schemaplexai.model.vo.spec;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 版本Diff对比结果VO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SpecDiffVO {

    /** 源版本号 */
    private Integer sourceVersion;

    /** 目标版本号 */
    private Integer targetVersion;

    /** 文档类型 */
    private String docType;

    /** 差异行列表 */
    private List<DiffLine> lines;

    /** 新增行数 */
    private int addedCount;

    /** 删除行数 */
    private int removedCount;

    /**
     * 差异行
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DiffLine {
        /** 行类型: added/removed/unchanged */
        private String type;
        /** 行内容 */
        private String content;
        /** 源文件行号（removed/unchanged有值） */
        private Integer sourceLineNumber;
        /** 目标文件行号（added/unchanged有值） */
        private Integer targetLineNumber;
    }
}
