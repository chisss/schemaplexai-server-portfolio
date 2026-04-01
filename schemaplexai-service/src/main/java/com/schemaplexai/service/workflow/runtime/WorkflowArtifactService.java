package com.schemaplexai.service.workflow.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.enums.SpecDocTypeEnum;
import com.schemaplexai.dao.mapper.SpecDocumentMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.dao.mapper.SpecVersionMapper;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.entity.SpecDocument;
import com.schemaplexai.model.entity.SpecVersion;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import com.schemaplexai.service.integration.git.GitOperationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowArtifactService {

    private final SpecMapper specMapper;
    private final SpecDocumentMapper specDocumentMapper;
    private final SpecVersionMapper specVersionMapper;
    private final GitOperationService gitOperationService;

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> persistAgentArtifactIfNecessary(WorkflowInstance instance,
                                                               WorkflowNodeExecution nodeExec,
                                                               String result) {
        if (!shouldPersist(instance, nodeExec) || !StringUtils.hasText(instance.getSpecId())) {
            return Map.of();
        }

        String markdown = normalizeMarkdown(instance, result);
        if (!StringUtils.hasText(markdown)) {
            throw new IllegalStateException("文档生成节点返回空内容，无法落地产物");
        }

        String docType = getVariable(instance, "artifactDocType", SpecDocTypeEnum.DESIGN.getCode());
        String operatorId = getVariable(instance, "triggerBy", null);
        saveSpecDocument(instance.getSpecId(), docType, markdown, operatorId);

        Map<String, Object> artifact = new HashMap<>();
        artifact.put("artifactSaved", true);
        artifact.put("artifactDocType", docType);

        String workspacePath = getVariable(instance, "workspacePath", null);
        String artifactOutputPath = getVariable(instance, "artifactOutputPath", null);
        if (StringUtils.hasText(workspacePath) && StringUtils.hasText(artifactOutputPath)) {
            Path absolutePath = writeArtifactFile(workspacePath, artifactOutputPath, markdown);
            artifact.put("artifactOutputPath", artifactOutputPath);
            artifact.put("artifactAbsolutePath", absolutePath.toString());
            commitArtifact(workspacePath, instance);

            Spec update = new Spec();
            update.setId(instance.getSpecId());
            update.setArtifactDocPath(artifactOutputPath);
            specMapper.updateById(update);
        }

        return artifact;
    }

    private boolean shouldPersist(WorkflowInstance instance, WorkflowNodeExecution nodeExec) {
        if (nodeExec == null || instance == null) {
            return false;
        }
        if (StringUtils.hasText(getVariable(instance, "artifactOutputPath", null))) {
            return "doc_gen".equalsIgnoreCase(nodeExec.getNodeId())
                    || StringUtils.hasText(nodeExec.getNodeLabel()) && nodeExec.getNodeLabel().contains("文档");
        }
        return "doc_gen".equalsIgnoreCase(nodeExec.getNodeId());
    }

    private String normalizeMarkdown(WorkflowInstance instance, String content) {
        String raw = unwrapMarkdownFence(content);
        if (!StringUtils.hasText(raw)) {
            return raw;
        }
        if (raw.stripLeading().startsWith("#")) {
            return raw.trim() + System.lineSeparator();
        }

        String title = getVariable(instance, "specName", "技术文档");
        return "# " + title + " 技术文档" + System.lineSeparator() + System.lineSeparator()
                + raw.trim() + System.lineSeparator();
    }

    private String unwrapMarkdownFence(String content) {
        if (!StringUtils.hasText(content)) {
            return content;
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            int firstLineBreak = trimmed.indexOf('\n');
            if (firstLineBreak > 0) {
                return trimmed.substring(firstLineBreak + 1, trimmed.length() - 3).trim();
            }
        }
        return trimmed;
    }

    private void saveSpecDocument(String specId, String docType, String content, String operatorId) {
        SpecDocument existing = specDocumentMapper.selectOne(new LambdaQueryWrapper<SpecDocument>()
                .eq(SpecDocument::getSpecId, specId)
                .eq(SpecDocument::getDocType, docType)
                .last("LIMIT 1"));

        if (existing == null) {
            existing = new SpecDocument();
            existing.setSpecId(specId);
            existing.setDocType(docType);
            existing.setVersion(1);
            existing.setCreatedBy(operatorId);
            existing.setCreatedAt(LocalDateTime.now());
            existing.setContent(content);
            existing.setUpdatedBy(operatorId);
            existing.setUpdatedAt(LocalDateTime.now());
            specDocumentMapper.insert(existing);
            createVersionSnapshot(specId, docType, content, 1, "工作流自动生成技术文档", operatorId);
            return;
        }

        int newVersion = existing.getVersion() != null ? existing.getVersion() + 1 : 1;
        existing.setContent(content);
        existing.setVersion(newVersion);
        existing.setUpdatedBy(operatorId);
        existing.setUpdatedAt(LocalDateTime.now());
        specDocumentMapper.updateById(existing);
        createVersionSnapshot(specId, docType, content, newVersion, "工作流自动更新技术文档", operatorId);
    }

    private void createVersionSnapshot(String specId,
                                       String docType,
                                       String content,
                                       int versionNumber,
                                       String changeSummary,
                                       String operatorId) {
        SpecVersion version = new SpecVersion();
        version.setSpecId(specId);
        version.setDocType(docType);
        version.setContent(content);
        version.setVersionNumber(versionNumber);
        version.setChangeSummary(changeSummary);
        version.setCreatedBy(operatorId);
        version.setCreatedAt(LocalDateTime.now());
        specVersionMapper.insert(version);
    }

    private Path writeArtifactFile(String workspacePath, String artifactOutputPath, String markdown) {
        try {
            Path absolutePath = Path.of(workspacePath).resolve(artifactOutputPath).normalize();
            Path parent = absolutePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(absolutePath, markdown, StandardCharsets.UTF_8);
            return absolutePath;
        } catch (IOException e) {
            throw new IllegalStateException("写入技术文档失败: " + artifactOutputPath, e);
        }
    }

    private void commitArtifact(String workspacePath, WorkflowInstance instance) {
        try {
            String ticket = getVariable(instance, "jiraTicket", null);
            String message = StringUtils.hasText(ticket)
                    ? "docs: generate technical design for " + ticket
                    : "docs: generate technical design";
            gitOperationService.commitChanges(workspacePath, message);
        } catch (Exception e) {
            log.warn("提交技术文档产物失败: workspacePath={}, error={}", workspacePath, e.getMessage());
        }
    }

    private String getVariable(WorkflowInstance instance, String key, String defaultValue) {
        if (instance.getVariables() == null) {
            return defaultValue;
        }
        Object value = instance.getVariables().get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }
}
