package com.schemaplexai.model.vo.workspace;

import lombok.Data;

/**
 * 分支差异文件视图
 */
@Data
public class BranchDiffFileVO {

    private String filePath;
    private String changeType;
    private Integer additions;
    private Integer deletions;
    private String patch;
}
