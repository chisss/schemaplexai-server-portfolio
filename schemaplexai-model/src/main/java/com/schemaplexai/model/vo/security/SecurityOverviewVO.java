package com.schemaplexai.model.vo.security;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 安全合规总览视图对象
 */
@Data
public class SecurityOverviewVO {

    private Long totalPolicies;

    private Long activePolicies;

    private Long draftPolicies;

    private Long criticalPolicies;

    private Long blockedEvents24h;

    private Long warningEvents24h;

    private Long openIncidents;

    private Long activeRulePacks;

    private LocalDateTime latestPublishedAt;

    private List<SecurityDomainOverviewVO> domainStats;

    private List<SecurityAuditEventVO> recentAuditEvents;

    private List<SecurityIncidentVO> recentIncidents;
}
