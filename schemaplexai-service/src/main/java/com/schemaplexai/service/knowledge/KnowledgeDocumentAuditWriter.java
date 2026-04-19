package com.schemaplexai.service.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AuditLogMapper;
import com.schemaplexai.model.dto.security.SecurityAuditContext;
import com.schemaplexai.model.entity.AuditLog;
import com.schemaplexai.service.security.SecurityAuditEventService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 知识文档审计写入器。
 *
 * <p>同时写入两条审计链路：
 * <ul>
 *   <li>{@code sf_audit_log} — 管理员"操作审计"视图，粒度=用户动作；</li>
 *   <li>{@code sf_security_audit_event} — 安全合规视图，粒度=安全事件（支持 block/warn 状态与策略关联）。</li>
 * </ul>
 *
 * <p>所有方法 <b>吞异常</b>：审计失败不得影响业务主流程，由调用方负责确保主事务完成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentAuditWriter {

    private final AuditLogMapper auditLogMapper;
    private final SecurityAuditEventService securityAuditEventService;
    private final ObjectMapper objectMapper;

    /**
     * 记录一条知识文档审计事件（双写）。
     *
     * @param event 事件上下文，必填字段：tenantId、eventType、eventTitle
     */
    public void record(KnowledgeAuditEvent event) {
        if (event == null) {
            return;
        }
        try {
            recordOperationAudit(event);
        } catch (Exception e) {
            log.warn("写入 sf_audit_log 失败（已忽略）: eventType={}, error={}", event.eventType, e.getMessage());
        }
        try {
            recordSecurityEvent(event);
        } catch (Exception e) {
            log.warn("写入 sf_security_audit_event 失败（已忽略）: eventType={}, error={}", event.eventType, e.getMessage());
        }
    }

    private void recordOperationAudit(KnowledgeAuditEvent event) {
        AuditLog log = new AuditLog();
        log.setTenantId(event.tenantId);
        log.setUserId(event.actorUserId != null ? event.actorUserId : SecurityUtil.getCurrentUserId());
        log.setUsername(event.actorUsername != null ? event.actorUsername : SecurityUtil.getCurrentUsername());
        log.setAction(event.action);
        log.setResource(SecurityComplianceConstant.RESOURCE_TYPE_KNOWLEDGE_DOCUMENT);
        log.setResourceId(event.documentId);
        log.setDetail(buildDetail(event));
        if (event.auditContext != null) {
            log.setIp(event.auditContext.getClientIp());
            log.setUserAgent(event.auditContext.getUserAgent());
        }
        log.setCreatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);
    }

    private void recordSecurityEvent(KnowledgeAuditEvent event) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (event.metadata != null) {
            metadata.putAll(event.metadata);
        }
        if (event.documentId != null) {
            metadata.put("documentId", event.documentId);
        }
        if (event.contentSha256 != null) {
            metadata.put("contentSha256", event.contentSha256);
        }
        if (event.fileName != null) {
            metadata.put("fileName", event.fileName);
        }

        securityAuditEventService.recordEvent(
                event.tenantId,
                event.traceId != null ? event.traceId : currentTraceId(),
                event.eventType,
                SecurityComplianceConstant.AUDIT_SOURCE_KNOWLEDGE_INGEST,
                event.eventStatus != null ? event.eventStatus : SecurityComplianceConstant.AUDIT_STATUS_SUCCESS,
                event.riskLevel != null ? event.riskLevel : SecurityComplianceConstant.RISK_LEVEL_LOW,
                SecurityComplianceConstant.DOMAIN_DATA,
                event.policyId,
                event.policyCode,
                SecurityComplianceConstant.RESOURCE_TYPE_KNOWLEDGE_DOCUMENT,
                event.documentId,
                event.eventTitle,
                event.eventDetail,
                metadata,
                event.auditContext
        );
    }

    private String buildDetail(KnowledgeAuditEvent event) {
        Map<String, Object> detail = new HashMap<>();
        detail.put("eventType", event.eventType);
        if (event.eventTitle != null) {
            detail.put("title", event.eventTitle);
        }
        if (event.fileName != null) {
            detail.put("fileName", event.fileName);
        }
        if (event.contentSha256 != null) {
            detail.put("contentSha256", event.contentSha256);
        }
        if (event.eventDetail != null) {
            detail.put("detail", event.eventDetail);
        }
        if (event.metadata != null) {
            detail.put("metadata", event.metadata);
        }
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            return event.eventTitle != null ? event.eventTitle : event.eventType;
        }
    }

    private static String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId != null ? traceId : UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 知识文档审计事件载体。使用 {@link Builder} 构造以便调用方按需填充字段。
     */
    @Data
    @Builder
    public static class KnowledgeAuditEvent {

        /** 租户 ID（必填） */
        private String tenantId;

        /** traceId；为空时从 MDC 获取 */
        private String traceId;

        /** 事件类型（{@link SecurityComplianceConstant#EVENT_KB_UPLOAD_REQUESTED} 等，必填） */
        private String eventType;

        /** sf_audit_log.action（如 {@link SecurityComplianceConstant#ACTION_KB_DOC_UPLOAD}） */
        private String action;

        /** 事件标题（必填，简短描述） */
        private String eventTitle;

        /** 事件详情（可选，长文本） */
        private String eventDetail;

        /** 事件状态 success/warning/blocked/failed；默认 success */
        private String eventStatus;

        /** 风险等级 low/medium/high/critical；默认 low */
        private String riskLevel;

        /** 文档 ID */
        private String documentId;

        /** 文件名（已脱敏） */
        private String fileName;

        /** 文件内容 SHA256 */
        private String contentSha256;

        /** 命中的策略 ID / code（可选） */
        private String policyId;
        private String policyCode;

        /** 审计上下文（IP/UA），可选 */
        private SecurityAuditContext auditContext;

        /** 操作人 ID/用户名；为空时回退到 SecurityUtil */
        private String actorUserId;
        private String actorUsername;

        /** 扩展元数据 */
        private Map<String, Object> metadata;
    }
}
