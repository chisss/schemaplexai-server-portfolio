package com.schemaplexai.service.workflow.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.enums.SpecDocTypeEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.SpecDocumentMapper;
import com.schemaplexai.dao.mapper.WorkspaceMapper;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.entity.SpecDocument;
import com.schemaplexai.model.entity.Workspace;
import com.schemaplexai.service.integration.git.GitWorkspaceOrchestrator;
import com.schemaplexai.service.mq.message.WorkflowTriggerMessage;
import com.schemaplexai.service.workspace.WorkspaceSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class SpecWorkflowRuntimeService {

    private static final Pattern JIRA_PATTERN = Pattern.compile("\\b([A-Z][A-Z0-9]+-\\d+)\\b");
    private static final int WORKFLOW_GOAL_SUMMARY_LIMIT = 240;

    private final SpecDocumentMapper specDocumentMapper;
    private final WorkspaceMapper workspaceMapper;
    private final GitWorkspaceOrchestrator gitWorkspaceOrchestrator;

    public Map<String, Object> buildRuntimeVariables(Spec spec, WorkflowTriggerMessage message) {
        Map<String, Object> variables = new HashMap<>();
        if (spec == null) {
            return variables;
        }

        putIfText(variables, "specId", spec.getId());
        putIfText(variables, "specName", spec.getName());
        putIfText(variables, "specDescription", spec.getDescription());
        putIfText(variables, "specType", spec.getSpecType());
        putIfPresent(variables, "profileData", spec.getProfileData());
        putIfText(variables, "docType", message.getDocType());
        putIfText(variables, "triggerType", message.getTriggerType());
        putIfText(variables, "triggerBy", message.getTriggerBy());
        putIfText(variables, "tenantId", message.getTenantId());
        putIfText(variables, "requestId", message.getRequestId());
        SpecDocument requirementsDoc = loadDocument(spec.getId(), SpecDocTypeEnum.REQUIREMENTS.getCode());
        SpecDocument designDoc = loadDocument(spec.getId(), SpecDocTypeEnum.DESIGN.getCode());
        SpecDocument tasksDoc = loadDocument(spec.getId(), SpecDocTypeEnum.TASKS.getCode());
        putIfPresent(variables, "requirementsDoc", buildDocumentReference(SpecDocTypeEnum.REQUIREMENTS.getCode(), requirementsDoc));
        putIfPresent(variables, "designDoc", buildDocumentReference(SpecDocTypeEnum.DESIGN.getCode(), designDoc));
        putIfPresent(variables, "tasksDoc", buildDocumentReference(SpecDocTypeEnum.TASKS.getCode(), tasksDoc));

        String jiraTicket = resolveJiraTicket(spec);
        String targetBranch = resolveTargetBranch(spec, jiraTicket);
        putIfText(variables, "jiraTicket", jiraTicket);
        putIfText(variables, "targetBranch", targetBranch);
        variables.put("semanticRetrievalMode", "mock");

        Workspace workspace = resolvePrimaryWorkspace(spec);
        if (workspace != null) {
            putIfText(variables, "workspaceId", workspace.getId());
            putIfText(variables, "workspaceName", workspace.getName());
            putIfText(variables, "workspaceDefaultBranch", workspace.getDefaultBranch());
            putIfText(variables, "workspaceLocalPath", workspace.getLocalPath());

            String artifactOutputPath = buildArtifactOutputPath(spec, jiraTicket);
            putIfText(variables, "artifactOutputPath", artifactOutputPath);

            if ("git".equalsIgnoreCase(workspace.getSourceType())
                    && StringUtils.hasText(message.getTenantId())
                    && StringUtils.hasText(message.getRequestId())) {
                WorkspaceSessionService.WorkspaceSession session = gitWorkspaceOrchestrator.prepareIsolatedWorkspace(
                        message.getTenantId(),
                        workspace.getId(),
                        defaultIfBlank(message.getTriggerBy(), "system"),
                        message.getRequestId(),
                        workspace.getDefaultBranch(),
                        targetBranch
                );
                putIfText(variables, "workspacePath", session.getWorktreePath());
                putIfText(variables, "workspaceBranch", session.getBranchName());
                putIfText(variables, "workspaceSessionId", session.getSessionId());
            } else {
                putIfText(variables, "workspacePath", workspace.getLocalPath());
                putIfText(variables, "workspaceBranch", targetBranch);
            }
        }

        putIfText(variables, "workflowGoal", buildWorkflowGoal(
                spec,
                workspace,
                jiraTicket,
                targetBranch,
                requirementsDoc != null ? requirementsDoc.getContent() : null
        ));
        variables.entrySet().removeIf(entry -> entry.getValue() == null);
        return variables;
    }

    public String resolveTargetBranch(Spec spec) {
        return resolveTargetBranch(spec, resolveJiraTicket(spec));
    }

    private String resolveTargetBranch(Spec spec, String jiraTicket) {
        if (isMarketingSpec(spec)) {
            return null;
        }
        if (spec != null && StringUtils.hasText(spec.getTargetBranch())) {
            return spec.getTargetBranch().trim();
        }
        if (StringUtils.hasText(jiraTicket)) {
            return "feature/" + jiraTicket;
        }
        return spec != null && StringUtils.hasText(spec.getId())
                ? "feature/spec-" + spec.getId().substring(0, Math.min(8, spec.getId().length()))
                : "feature/spec-runtime";
    }

    private String resolveJiraTicket(Spec spec) {
        if (spec == null) {
            return null;
        }
        if (StringUtils.hasText(spec.getJiraTicket())) {
            return spec.getJiraTicket().trim().toUpperCase();
        }

        String[] candidates = {
                spec.getDescription(),
                spec.getName(),
                spec.getTargetBranch()
        };
        for (String candidate : candidates) {
            if (!StringUtils.hasText(candidate)) {
                continue;
            }
            Matcher matcher = JIRA_PATTERN.matcher(candidate.toUpperCase());
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private Workspace resolvePrimaryWorkspace(Spec spec) {
        String workspaceId = null;
        if (spec.getWorkspaceIds() != null && !spec.getWorkspaceIds().isEmpty()) {
            workspaceId = spec.getWorkspaceIds().get(0);
        } else if (StringUtils.hasText(spec.getProjectId())) {
            workspaceId = spec.getProjectId();
        }
        if (!StringUtils.hasText(workspaceId)) {
            return null;
        }

        Workspace workspace = workspaceMapper.selectById(workspaceId);
        if (workspace == null) {
            throw new BusinessException(ResultCode.WORKSPACE_NOT_FOUND, "Spec 关联工作空间不存在: " + workspaceId);
        }
        return workspace;
    }

    private SpecDocument loadDocument(String specId, String docType) {
        if (!StringUtils.hasText(specId)) {
            return null;
        }
        return specDocumentMapper.selectOne(new LambdaQueryWrapper<SpecDocument>()
                .eq(SpecDocument::getSpecId, specId)
                .eq(SpecDocument::getDocType, docType)
                .last("LIMIT 1"));
    }

    private String buildArtifactOutputPath(Spec spec, String jiraTicket) {
        if (isMarketingSpec(spec)) {
            String baseName = sanitizeFileSegment(spec != null ? spec.getName() : "marketing-artifact");
            return "marketing/" + baseName + "/";
        }
        String fileKey = StringUtils.hasText(jiraTicket)
                ? jiraTicket
                : sanitizeFileSegment(spec != null ? spec.getName() : "spec");
        return "docs/" + fileKey + "-technical-design.md";
    }

    private String buildWorkflowGoal(Spec spec, Workspace workspace, String jiraTicket,
                                     String targetBranch, String requirementsDoc) {
        StringBuilder goal = new StringBuilder("围绕当前 Spec 推进标准研发工作流，默认优先使用上游节点传递的信息作为当前任务输入");
        if (spec != null && StringUtils.hasText(spec.getName())) {
            goal.append("。Spec名称：").append(spec.getName().trim());
        }

        String summary = summarizeGoalText(spec != null ? spec.getDescription() : null);
        if (!StringUtils.hasText(summary)) {
            summary = summarizeGoalText(requirementsDoc);
        }
        if (StringUtils.hasText(summary)) {
            goal.append("。需求摘要：").append(summary);
        }
        if (isMarketingSpec(spec)) {
            goal.append("。这是营销类需求，不需要 Jira 编号和研发分支，最终需要输出可投放的营销文案产物包");
        } else {
            goal.append("。最终需要在对应工作流节点产出与节点目标一致的 Markdown 文档或执行结果");
        }
        if (workspace != null && StringUtils.hasText(workspace.getName())) {
            goal.append("，需要落地文档时写入工作空间[").append(workspace.getName().trim()).append("]");
        } else {
            goal.append("，需要落地文档时写入绑定工作空间");
        }
        if (StringUtils.hasText(jiraTicket)) {
            goal.append("。关联 Jira：").append(jiraTicket);
        }
        if (StringUtils.hasText(targetBranch)) {
            goal.append("。目标分支：").append(targetBranch);
        }
        return goal.toString();
    }

    private String sanitizeFileSegment(String value) {
        String raw = StringUtils.hasText(value) ? value.trim().toLowerCase() : "spec";
        String sanitized = raw.replaceAll("[^a-z0-9._-]+", "-").replaceAll("-{2,}", "-");
        return sanitized.replaceAll("^-|-$", "");
    }

    private String summarizeGoalText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        return normalized.length() > WORKFLOW_GOAL_SUMMARY_LIMIT
                ? normalized.substring(0, WORKFLOW_GOAL_SUMMARY_LIMIT) + "..."
                : normalized;
    }

    private void putIfText(Map<String, Object> variables, String key, String value) {
        if (StringUtils.hasText(value)) {
            variables.put(key, value);
        }
    }

    private void putIfPresent(Map<String, Object> variables, String key, Object value) {
        if (value != null) {
            variables.put(key, value);
        }
    }

    private boolean isMarketingSpec(Spec spec) {
        return spec != null && "marketing".equalsIgnoreCase(spec.getSpecType());
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    private Map<String, Object> buildDocumentReference(String docType, SpecDocument document) {
        if (document == null || !StringUtils.hasText(document.getContent())) {
            return null;
        }
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("docId", document.getId());
        reference.put("docType", docType);
        reference.put("version", document.getVersion());
        reference.put("summary", summarizeGoalText(document.getContent()));
        reference.put("contentLength", document.getContent().length());
        reference.put("updatedAt", document.getUpdatedAt() != null ? document.getUpdatedAt().toString() : null);
        return reference;
    }
}
