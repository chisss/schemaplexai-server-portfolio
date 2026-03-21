package com.schemaplexai.service.monitor;

import com.schemaplexai.model.vo.monitor.ActiveAgentVO;
import com.schemaplexai.model.vo.monitor.DashboardVO;
import com.schemaplexai.model.vo.monitor.SystemHealthVO;
import com.schemaplexai.model.vo.monitor.TaskQueueVO;

import java.util.List;

/**
 * 监控仪表盘服务
 */
public interface DashboardService {

    DashboardVO getDashboard();

    List<ActiveAgentVO> getActiveAgents();

    TaskQueueVO getTaskQueue();

    SystemHealthVO getSystemHealth();
}
