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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class SpecWorkflowRuntimeService {

    private static final Pattern JIRA_PATTERN = Pattern.compile("\\b([A-Z][A-Z0-9]+-\\d+)\\b");

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
        putIfText(variables, "docType", message.getDocType());
        putIfText(variables, "triggerType", message.getTriggerType());
        putIfText(variables, "triggerBy", message.getTriggerBy());
        putIfText(variables, "tenantId", message.getTenantId());
        putIfText(variables, "requestId", message.getRequestId());
        putIfText(variables, "requirementsDoc", loadDocument(spec.getId(), SpecDocTypeEnum.REQUIREMENTS.getCode()));
        putIfText(variables, "designDoc", loadDocument(spec.getId(), SpecDocTypeEnum.DESIGN.getCode()));
        putIfText(variables, "tasksDoc", loadDocument(spec.getId(), SpecDocTypeEnum.TASKS.getCode()));

        String jiraTicket = resolveJiraTicket(spec);
        String targetBranch = resolveTargetBranch(spec, jiraTicket);
        putIfText(variables, "jiraTicket", jiraTicket);
        putIfText(variables, "targetBranch", targetBranch);
        variables.put("artifactDocType", SpecDocTypeEnum.DESIGN.getCode());
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

        variables.put("workflowGoal", "阅读当前系统中导入的项目 titanium-policy，生成 md 格式的技术文档，并将产物落到绑定工作空间。");
        variables.entrySet().removeIf(entry -> entry.getValue() == null);
        return variables;
    }

    public String resolveTargetBranch(Spec spec) {
        return resolveTargetBranch(spec, resolveJiraTicket(spec));
    }

    private String resolveTargetBranch(Spec spec, String jiraTicket) {
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

    private String loadDocument(String specId, String docType) {
        if (!StringUtils.hasText(specId)) {
            return null;
        }
        SpecDocument document = specDocumentMapper.selectOne(new LambdaQueryWrapper<SpecDocument>()
                .eq(SpecDocument::getSpecId, specId)
                .eq(SpecDocument::getDocType, docType)
                .last("LIMIT 1"));
        return document != null ? document.getContent() : null;
    }

    private String buildArtifactOutputPath(Spec spec, String jiraTicket) {
        String fileKey = StringUtils.hasText(jiraTicket)
                ? jiraTicket
                : sanitizeFileSegment(spec != null ? spec.getName() : "spec");
        return "docs/" + fileKey + "-technical-design.md";
    }

    private String sanitizeFileSegment(String value) {
        String raw = StringUtils.hasText(value) ? value.trim().toLowerCase() : "spec";
        String sanitized = raw.replaceAll("[^a-z0-9._-]+", "-").replaceAll("-{2,}", "-");
        return sanitized.replaceAll("^-|-$", "");
    }

    private void putIfText(Map<String, Object> variables, String key, String value) {
        if (StringUtils.hasText(value)) {
            variables.put(key, value);
        }
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }
}
