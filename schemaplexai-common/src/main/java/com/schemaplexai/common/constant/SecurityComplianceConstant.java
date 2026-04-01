package com.schemaplexai.common.constant;

import java.util.List;

/**
 * 安全合规模块常量
 */
public final class SecurityComplianceConstant {

    private SecurityComplianceConstant() {
    }

    public static final String DOMAIN_RUNTIME = "runtime";
    public static final String DOMAIN_DATA = "data";
    public static final String DOMAIN_RISK = "risk";
    public static final String DOMAIN_INDUSTRY = "industry";
    public static final List<String> SUPPORTED_DOMAINS = List.of(
            DOMAIN_RUNTIME,
            DOMAIN_DATA,
            DOMAIN_RISK,
            DOMAIN_INDUSTRY
    );

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_INACTIVE = "inactive";
    public static final String STATUS_ARCHIVED = "archived";
    public static final List<String> POLICY_STATUSES = List.of(
            STATUS_DRAFT,
            STATUS_ACTIVE,
            STATUS_INACTIVE,
            STATUS_ARCHIVED
    );

    public static final String RISK_LEVEL_LOW = "low";
    public static final String RISK_LEVEL_MEDIUM = "medium";
    public static final String RISK_LEVEL_HIGH = "high";
    public static final String RISK_LEVEL_CRITICAL = "critical";
    public static final List<String> RISK_LEVELS = List.of(
            RISK_LEVEL_LOW,
            RISK_LEVEL_MEDIUM,
            RISK_LEVEL_HIGH,
            RISK_LEVEL_CRITICAL
    );

    public static final String ENFORCEMENT_AUDIT = "audit";
    public static final String ENFORCEMENT_WARN = "warn";
    public static final String ENFORCEMENT_BLOCK = "block";
    public static final List<String> ENFORCEMENT_MODES = List.of(
            ENFORCEMENT_AUDIT,
            ENFORCEMENT_WARN,
            ENFORCEMENT_BLOCK
    );

    public static final String SCOPE_TENANT = "tenant";
    public static final String SCOPE_AGENT = "agent";
    public static final String SCOPE_WORKFLOW = "workflow";
    public static final String SCOPE_PROJECT = "project";
    public static final List<String> POLICY_SCOPES = List.of(
            SCOPE_TENANT,
            SCOPE_AGENT,
            SCOPE_WORKFLOW,
            SCOPE_PROJECT
    );

    public static final String AUDIT_STATUS_SUCCESS = "success";
    public static final String AUDIT_STATUS_WARNING = "warning";
    public static final String AUDIT_STATUS_BLOCKED = "blocked";
    public static final String AUDIT_STATUS_FAILED = "failed";
    public static final List<String> AUDIT_STATUSES = List.of(
            AUDIT_STATUS_SUCCESS,
            AUDIT_STATUS_WARNING,
            AUDIT_STATUS_BLOCKED,
            AUDIT_STATUS_FAILED
    );

    public static final String AUDIT_SOURCE_STRATEGY_CENTER = "strategy_center";
    public static final String RESOURCE_TYPE_POLICY = "security_policy";

    public static final String EVENT_POLICY_CREATED = "policy_created";
    public static final String EVENT_POLICY_UPDATED = "policy_updated";
    public static final String EVENT_POLICY_PUBLISHED = "policy_published";
    public static final String EVENT_POLICY_TOGGLED = "policy_toggled";
    public static final String EVENT_POLICY_DELETED = "policy_deleted";
    public static final String EVENT_POLICY_BOOTSTRAPPED = "policy_bootstrapped";

    public static final String EVENT_RUNTIME_CHECK = "runtime_check";
    public static final String EVENT_RUNTIME_WARNING = "runtime_warning";
    public static final String EVENT_RUNTIME_BLOCKED = "runtime_blocked";
    public static final String EVENT_RUNTIME_PAUSED = "runtime_paused";

    public static final String AUDIT_SOURCE_RUNTIME_ENGINE = "runtime_engine";
    public static final String AUDIT_SOURCE_AGENT_EXECUTION = "agent_execution";
    public static final String AUDIT_SOURCE_WORKFLOW_EXECUTION = "workflow_execution";
    public static final String AUDIT_SOURCE_RULE_PACK = "rule_pack";

    public static final String RESOURCE_TYPE_INCIDENT = "security_incident";
    public static final String RESOURCE_TYPE_RULE_PACK = "security_rule_pack";
    public static final String RESOURCE_TYPE_AGENT = "agent";
    public static final String RESOURCE_TYPE_AGENT_EXECUTION = "agent_execution";
    public static final String RESOURCE_TYPE_WORKFLOW_INSTANCE = "workflow_instance";
    public static final String RESOURCE_TYPE_WORKFLOW_NODE = "workflow_node";
    public static final String RESOURCE_TYPE_RULE_ITEM = "security_rule_item";

    public static final String SOURCE_TYPE_POLICY = "policy";
    public static final String SOURCE_TYPE_RULE_PACK = "rule_pack";

    public static final String INCIDENT_STATUS_NEW = "new";
    public static final String INCIDENT_STATUS_ASSIGNED = "assigned";
    public static final String INCIDENT_STATUS_INVESTIGATING = "investigating";
    public static final String INCIDENT_STATUS_RESOLVED = "resolved";
    public static final String INCIDENT_STATUS_IGNORED = "ignored";
    public static final String INCIDENT_STATUS_ESCALATED = "escalated";
    public static final List<String> INCIDENT_STATUSES = List.of(
            INCIDENT_STATUS_NEW,
            INCIDENT_STATUS_ASSIGNED,
            INCIDENT_STATUS_INVESTIGATING,
            INCIDENT_STATUS_RESOLVED,
            INCIDENT_STATUS_IGNORED,
            INCIDENT_STATUS_ESCALATED
    );

    public static final String ACTION_ASSIGN = "assign";
    public static final String ACTION_RESOLVE = "resolve";
    public static final String ACTION_IGNORE = "ignore";
    public static final String ACTION_ESCALATE = "escalate";
    public static final String ACTION_COMMENT = "comment";
    public static final String ACTION_RESUME_RESOURCE = "resume_resource";

    public static final String DECISION_ALLOW = "allow";
    public static final String DECISION_WARN = "warn";
    public static final String DECISION_BLOCK = "block";
    public static final String DECISION_PAUSE = "pause";
    public static final List<String> CHECK_DECISIONS = List.of(
            DECISION_ALLOW,
            DECISION_WARN,
            DECISION_BLOCK,
            DECISION_PAUSE
    );

    public static final String RULE_PACK_STATUS_DRAFT = STATUS_DRAFT;
    public static final String RULE_PACK_STATUS_ACTIVE = STATUS_ACTIVE;
    public static final String RULE_PACK_STATUS_INACTIVE = STATUS_INACTIVE;
    public static final String RULE_PACK_STATUS_ARCHIVED = STATUS_ARCHIVED;

    public static final String CHECK_SCENE_RUNTIME = "runtime";
    public static final String CHECK_SCENE_AGENT_EXECUTE = "agent_execute";
    public static final String CHECK_SCENE_WORKFLOW_START = "workflow_start";
    public static final String CHECK_SCENE_WORKFLOW_NODE = "workflow_node";
    public static final String CHECK_SCENE_TOOL_PRE = "tool_pre";
    public static final String CHECK_SCENE_TOOL_POST = "tool_post";
    public static final String CHECK_SCENE_OUTPUT = "output";
}
