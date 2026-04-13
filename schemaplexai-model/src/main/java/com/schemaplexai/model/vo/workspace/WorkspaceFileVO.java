package com.schemaplexai.model.vo.workspace;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作空间文件视图
 */
@Data
public class WorkspaceFileVO {

    private String path;

    private String name;

    private Boolean directory;

    private Long size;

    private LocalDateTime modifiedAt;

    private Boolean previewable;

    private Boolean downloadable;
}
