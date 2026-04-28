package com.schemaplexai.model.vo.workspace;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 工作空间文件内容视图
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WorkspaceFileContentVO extends WorkspaceFileVO {

    private String mimeType;

    private Boolean truncated;

    private String content;
}
