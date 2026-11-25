package com.schemaplexai.service.desktop;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.model.dto.agent.AgentExecutionInputDTO;
import com.schemaplexai.model.dto.security.SecurityAuditContext;
import com.schemaplexai.model.dto.security.SecurityIncidentActionRequest;
import com.schemaplexai.service.quality.feedback.QualityIssueFeedbackService;
import com.schemaplexai.service.security.SecurityIncidentService;
import com.schemaplexai.model.dto.workflow.ReviewDecisionRequest;
import com.schemaplexai.service.agent.AgentService;
import com.schemaplexai.service.desktop.DesktopEmployeeAccess.Employee;
import com.schemaplexai.service.security.AuthorizationService;
import com.schemaplexai.service.workflow.ReviewSessionService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class DesktopApprovalService {
    private static final String ITEMS = """
        WITH employee AS (SELECT CAST(? AS uuid) tenant, CAST(? AS uuid) uid),
        items AS (
          SELECT CAST(s.id AS text), 'workflow_review' type, '工作流评审' title, s.document_type summary,
            s.decision_status state, CAST(s.owner AS text) applicant, CAST(e.uid AS text) assignee,
            CAST(s.workflow_instance_id AS text) source_id,
            CAST(s.workflow_node_id AS text) node_id, CAST(null AS text) execution_id, s.created_at,
            s.updated_at, s.deadline expires_at, CAST(null AS text) evidence,
            s.decision_status='pending' AND member.value->>'status' IN ('pending','submitted') pending
          FROM sf_review_session s JOIN employee e ON s.tenant_id=e.tenant
            JOIN LATERAL (SELECT value FROM jsonb_array_elements(COALESCE(CAST(s.reviewers AS jsonb),'[]')) value
                WHERE value->>'userId'=CAST(e.uid AS text) LIMIT 1) member ON true
          WHERE s.deleted=0
          UNION ALL
          SELECT CAST(p.id AS text),'agent_tool',coalesce(p.tool_name,p.tool_code),p.risk_level,p.decision_status,
                 CAST(a.created_by AS text),CAST(e.uid AS text),CAST(p.agent_id AS text),NULL,
                 CAST(p.execution_id AS text),p.created_at,p.updated_at,p.expires_at,
                 CAST(p.tool_arguments AS text),p.decision_status='pending'
          FROM sf_pending_tool_approval p JOIN sf_agent_execution a ON a.id=p.execution_id AND a.tenant_id=p.tenant_id
            JOIN employee e ON p.tenant_id=e.tenant AND a.created_by=e.uid
          WHERE p.deleted=0 AND a.deleted=0 AND jsonb_extract_path(CAST(a.input_context AS jsonb),'desktopDeviceId') IS NULL
            AND jsonb_extract_path(CAST(a.input_context AS jsonb),'desktopWorkspaceId') IS NULL
          UNION ALL
          SELECT CAST(q.id AS text),'quality_issue',q.title,q.summary,q.status,CAST(q.created_by AS text),
                 CAST(e.uid AS text),CAST(q.id AS text),NULL,NULL,
                 q.created_at,q.updated_at,NULL,NULL,q.status IN ('open','acknowledged')
          FROM sf_quality_issue q JOIN employee e ON CAST(q.tenant_id AS text)=CAST(e.tenant AS text)
            AND q.assignee_id=CAST(e.uid AS text) WHERE q.deleted=0
          UNION ALL
          SELECT CAST(s.id AS text),'security_incident',s.event_title,s.risk_level,s.status,
                 CAST(s.created_by AS text),CAST(e.uid AS text),CAST(s.id AS text),NULL,NULL,
                 s.created_at,s.updated_at,NULL,NULL,s.status IN ('new','assigned','investigating','escalated')
          FROM sf_security_incident s JOIN employee e ON CAST(s.tenant_id AS text)=CAST(e.tenant AS text)
            AND s.assigned_to=e.uid WHERE s.deleted=0
        )
        """;
    private final JdbcTemplate jdbc;
    private final ReviewSessionService reviews;
    private final AgentService agents;
    private final AuthorizationService authorization;
    private final QualityIssueFeedbackService qualityIssues;
    private final SecurityIncidentService incidents;

    public record Source(String kind, String id, String executionId, String nodeId) {}
    public record Item(String id, String type, String title, String summary, String state, Source source,
                       String applicant, String assignee, LocalDateTime createdAt, LocalDateTime expiresAt,
                       String revision, List<String> allowedActions, String evidence) {}
    public record Decision(@NotBlank String action, @Size(max = 2000) String comment,
                           @NotNull String expectedRevision, @NotBlank @Size(max = 128) String clientRequestId) {}

    public PageResult<Item> list(boolean pending, String type, int page, int size) {
        var employee = Employee.current();
        String where = " FROM items WHERE (pending AND (expires_at IS NULL OR expires_at>now()))=?"
                + " AND (CAST(? AS varchar) IS NULL OR type=?)";
        long count = jdbc.queryForObject(ITEMS + "SELECT count(*)" + where, Long.class,
                employee.tenantId(), employee.userId(), pending, type, type);
        var rows = jdbc.query(ITEMS + "SELECT *" + where + " ORDER BY created_at DESC,id LIMIT ? OFFSET ?", mapper(),
                employee.tenantId(), employee.userId(), pending, type, type, size, DesktopWorkspaceService.offset(page, size));
        return new PageResult<>(rows, count, page, size);
    }

    public Item detail(String type, String id) {
        var employee = Employee.current();
        return jdbc.query(ITEMS + "SELECT * FROM items WHERE type=? AND id=?", mapper(),
                employee.tenantId(), employee.userId(), type, id).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND));
    }

    @Transactional
    public Item decide(String type, String id, Decision command, SecurityAuditContext auditContext) {
        Item item = detail(type, id);
        String table = switch (type) {
            case "workflow_review" -> "sf_review_session";
            case "agent_tool" -> "sf_pending_tool_approval";
            case "quality_issue" -> "sf_quality_issue";
            case "security_incident" -> "sf_security_incident";
            default -> throw new BusinessException(ResultCode.FORBIDDEN);
        };
        var employee = Employee.current();
        jdbc.queryForObject("SELECT id FROM " + table + " WHERE tenant_id=? AND id=? FOR UPDATE",
                String.class, employee.tenantId(), id);
        item = detail(type, id);
        var existing = jdbc.query("""
                SELECT approval_type,approval_id,action,comment FROM sf_desktop_approval_decision
                WHERE tenant_id=? AND owner_id=? AND request_id=?
                """, (rs, index) -> new String[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)},
                employee.tenantId(), employee.userId(), command.clientRequestId());
        if (!existing.isEmpty()) {
            var previous = existing.getFirst();
            if (type.equals(previous[0]) && id.equals(previous[1]) && command.action().equals(previous[2])
                    && Objects.equals(command.comment(), previous[3])) return item;
            throw new BusinessException(409, "请求编号已用于其他审批");
        }
        if (!item.revision().equals(command.expectedRevision()) || !item.allowedActions().contains(command.action())) {
            throw new BusinessException(409, "审批已处理、已过期或当前用户的权限已变更");
        }
        jdbc.update("""
                INSERT INTO sf_desktop_approval_decision(tenant_id,owner_id,request_id,approval_type,approval_id,action,comment)
                VALUES(?,?,?,?,?,?,?)
                """, employee.tenantId(), employee.userId(), command.clientRequestId(), type, id, command.action(), command.comment());
        String userId = employee.userId();
        switch (type) {
            case "agent_tool" -> {
                AgentExecutionInputDTO input = new AgentExecutionInputDTO();
                input.setApprovalId(id);
                input.setApprovalDecision("approve".equals(command.action()) ? "approve" : "deny");
                agents.submitExecutionInput(item.source().id(), item.source().executionId(), input);
            }
            case "workflow_review" -> {
                ReviewDecisionRequest request = new ReviewDecisionRequest();
                request.setComment(command.comment());
                if ("approve".equals(command.action())) reviews.approve(id, request);
                else reviews.reject(id, request);
            }
            case "quality_issue" -> {
                switch (command.action()) {
                    case "acknowledge" -> qualityIssues.acknowledgeIssue(id, userId);
                    case "resolve" -> qualityIssues.resolveIssue(id, "resolved", command.comment(), userId);
                    case "ignore" -> qualityIssues.ignoreIssue(id, command.comment(), userId);
                    default -> throw new BusinessException(ResultCode.FORBIDDEN);
                }
            }
            case "security_incident" -> {
                SecurityIncidentActionRequest request = new SecurityIncidentActionRequest();
                request.setComment(command.comment());
                if ("resolve".equals(command.action())) {
                    incidents.resolve(id, request, auditContext);
                } else if ("ignore".equals(command.action())) {
                    incidents.ignore(id, request, auditContext);
                } else {
                    throw new BusinessException(ResultCode.FORBIDDEN);
                }
            }
            default -> throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return detail(type, id);
    }

    private RowMapper<Item> mapper() {
        boolean workflowDecision = authorization.hasPermission("approval:decide");
        boolean agentDecision = authorization.hasPermission("agent:execute");
        boolean qualityDecision = authorization.hasPermission("quality:manage");
        boolean securityDecision = authorization.hasPermission("security:policy:manage");
        return (rs, index) -> {
            var type = rs.getString("type");
            Timestamp expiry = rs.getTimestamp("expires_at");
            boolean pending = rs.getBoolean("pending") && (expiry == null || expiry.toLocalDateTime().isAfter(LocalDateTime.now()));
            List<String> actions = List.of();
            if (pending) {
                actions = switch (type) {
                    case "workflow_review" -> workflowDecision ? List.of("approve", "reject") : List.of();
                    case "agent_tool" -> agentDecision ? List.of("approve", "reject") : List.of();
                    case "quality_issue" -> qualityDecision
                            ? ("open".equals(rs.getString("state"))
                                ? List.of("acknowledge", "resolve", "ignore") : List.of("resolve", "ignore"))
                            : List.of();
                    case "security_incident" -> securityDecision ? List.of("resolve", "ignore") : List.of();
                    default -> List.of();
                };
            }
            return new Item(rs.getString("id"), type, rs.getString("title"), rs.getString("summary"),
                    rs.getString("state"), new Source(type, rs.getString("source_id"), rs.getString("execution_id"),
                    rs.getString("node_id")), rs.getString("applicant"), rs.getString("assignee"),
                    rs.getTimestamp("created_at").toLocalDateTime(), expiry == null ? null : expiry.toLocalDateTime(),
                    String.valueOf(rs.getTimestamp("updated_at")) + ":" + rs.getString("state") + ":" + pending,
                    actions, rs.getString("evidence"));
        };
    }
}
