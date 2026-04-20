package com.schemaplexai.model.vo.homepage;

import lombok.Data;

import java.util.List;

/**
 * 左栏任务面板数据
 */
@Data
public class TaskPanelVO {

    private List<TaskItemVO> inProgressTasks;
    private List<TaskItemVO> blockedTasks;
    private List<TaskItemVO> pendingApprovals;
    private List<QuickEntryVO> quickEntries;
}
