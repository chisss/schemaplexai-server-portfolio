package com.schemaplexai.model.vo.homepage;

import com.schemaplexai.model.vo.monitor.SystemHealthVO;
import lombok.Data;

import java.util.List;

/**
 * 右栏态势面板数据
 */
@Data
public class SituationPanelVO {

    private SystemHealthVO systemHealth;
    private List<RiskAlertVO> riskAlerts;
    private List<BlockChainVO> blockChains;
    private List<AuditTrailVO> recentAuditTrails;
    private TrendSummaryVO anomalyTrend;
}
