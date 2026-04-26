package com.schemaplexai.service.workflow.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.enums.SpecDocTypeEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.NotificationChannelMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.model.entity.NotificationChannel;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.entity.SpecDocument;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import com.schemaplexai.model.dto.spec.SpecDocumentRequest;
import com.schemaplexai.service.artifact.ArtifactService;
import com.schemaplexai.service.integration.feishu.FeishuDocDeliveryService;
import com.schemaplexai.service.integration.git.GitOperationService;
import com.schemaplexai.service.spec.handler.SpecVersionHandler;
import com.schemaplexai.service.workflow.ArtifactSceneResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowArtifactService {

    private static final String RUNTIME_METADATA_TITLE = "## 已确认运行时元数据";
    private static final String ARTIFACT_TYPE_MARKETING_BUNDLE = "marketing_copy_bundle";
    private static final Pattern MARKDOWN_BLOCK_PATTERN = Pattern.compile("(?s)```(?:markdown|md)\\s*\\n(.*?)\\n```");
    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("(?s)```(?:json)\\s*\\n(.*?)\\n```");
    private static final Pattern GENERIC_FENCE_PATTERN = Pattern.compile("(?s)```\\s*\\n(.*?)\\n```");
    private static final Pattern OUTPUT_PATH_PATTERN = Pattern.compile("(?:输出路径|outputPath|artifactOutputPath)[^`\\n]*`([^`]+)`");
    private static final Pattern XML_TOOL_CALL_BLOCK_PATTERN = Pattern.compile("(?is)<[A-Za-z0-9_:-]*tool_call>.*?</[A-Za-z0-9_:-]*tool_call>");
    private static final Pattern XML_TOOL_INVOKE_BLOCK_PATTERN = Pattern.compile("(?is)<invoke\\b[^>]*>.*?</invoke>");
    private static final Pattern XML_TOOL_TAG_PATTERN = Pattern.compile("(?is)</?(?:invoke|parameter)\\b[^>]*>");
    private static final Pattern ABSOLUTE_WORKSPACE_PATH_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9._/-])((?:/Users|/home)/[^\\s<`]+)",
            Pattern.CASE_INSENSITIVE
    );

    private final SpecMapper specMapper;
    private final SpecVersionHandler specVersionHandler;
    private final GitOperationService gitOperationService;
    private final ArtifactService artifactService;
    private final ObjectMapper objectMapper;
    private final NotificationChannelMapper notificationChannelMapper;
    private final FeishuDocDeliveryService feishuDocDeliveryService;

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> persistAgentArtifactIfNecessary(WorkflowInstance instance,
                                                               WorkflowNodeExecution nodeExec,
                                                               Map<String, Object> nodeConfig,
                                                               String result) {
        return persistAgentArtifactIfNecessary(instance, nodeExec, nodeConfig, result, Map.of());
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> persistAgentArtifactIfNecessary(WorkflowInstance instance,
                                                               WorkflowNodeExecution nodeExec,
                                                               Map<String, Object> nodeConfig,
                                                               String result,
                                                               Map<String, Object> runtimeMetadata) {
        if (!shouldPersist(instance, nodeExec, nodeConfig)) {
            return Map.of();
        }
        boolean specBound = StringUtils.hasText(instance.getSpecId());

        String workspacePath = getVariable(instance, "workspacePath", null);
        String declaredArtifactOutputPath = extractDeclaredArtifactPath(result);
        String artifactOutputPath = resolveArtifactOutputPath(
                instance,
                nodeExec,
                nodeConfig,
                declaredArtifactOutputPath
        );
        boolean workspaceArtifactEnabled = StringUtils.hasText(workspacePath) && StringUtils.hasText(artifactOutputPath);

        ArtifactPayload payload = resolveArtifactPayload(
                instance,
                nodeExec,
                nodeConfig,
                result,
                artifactOutputPath,
                workspacePath
        );
        String markdown = payload.content();
        if (!StringUtils.hasText(markdown)) {
            throw new IllegalStateException("文档生成节点返回空内容，无法落地产物");
        }

        String docType = resolveArtifactDocType(instance, nodeExec, nodeConfig);
        Integer expectedDocVersion = resolveExpectedDocVersion(
                instance.getSpecId(),
                docType,
                nodeExec != null ? nodeExec.getNodeId() : null
        );
        if (!ARTIFACT_TYPE_MARKETING_BUNDLE.equalsIgnoreCase(payload.artifactType())) {
            markdown = enrichArtifactContentWithRuntimeMetadata(
                    markdown,
                    instance,
                    nodeExec,
                    nodeConfig,
                    docType,
                    expectedDocVersion,
                    runtimeMetadata
            );
        }
        payload = payload.withContent(markdown);

        Map<String, Object> artifact = new HashMap<>();
        artifact.put("artifactContent", markdown);
        artifact.put("artifactType", payload.artifactType());
        artifact.put("artifactFormat", payload.format());
        artifact.put("rawContent", markdown);
        artifact.put("agentRawContent", sanitizeArtifactContent(result));
        if (StringUtils.hasText(docType)) {
            artifact.put("artifactDocType", docType);
            if (specBound) {
                SpecDocument savedDocument = saveSpecDocument(instance.getSpecId(), docType, markdown, nodeExec.getNodeId());
                artifact.put("artifactSaved", true);
                if (savedDocument != null) {
                    artifact.put("artifactDocId", savedDocument.getId());
                    artifact.put("artifactDocVersion", savedDocument.getVersion());
                } else if (expectedDocVersion != null) {
                    artifact.put("artifactDocVersion", expectedDocVersion);
                }
            } else {
                artifact.put("artifactSaved", false);
            }
        }

        List<ArtifactService.WorkspaceDeliveryTarget> deliveryTargets = new ArrayList<>();
        List<String> committedPaths = new ArrayList<>();
        if (workspaceArtifactEnabled
                && StringUtils.hasText(workspacePath)
                && StringUtils.hasText(artifactOutputPath)) {
            List<ArtifactFileWriteResult> fileResults = writeArtifactFiles(workspacePath, payload.files());
            if (!fileResults.isEmpty()) {
                ArtifactFileWriteResult primaryFile = fileResults.get(0);
                artifact.put("artifactOutputPath", primaryFile.relativePath());
                artifact.put("artifactAbsolutePath", primaryFile.absolutePath().toString());
            }
            fileResults.forEach(item -> {
                committedPaths.add(item.relativePath());
                deliveryTargets.add(new ArtifactService.WorkspaceDeliveryTarget(
                        getVariable(instance, "workspaceId", null),
                        item.relativePath(),
                        null,
                        "workspace_file",
                        "工作流已写入工作空间文件",
                        Map.of("absolutePath", item.absolutePath().toString())
                ));
            });
            commitArtifact(workspacePath, committedPaths, instance);

            if (specBound) {
                Spec update = new Spec();
                update.setId(instance.getSpecId());
                update.setArtifactDocPath(artifact.containsKey("artifactOutputPath")
                        ? String.valueOf(artifact.get("artifactOutputPath")) : artifactOutputPath);
                specMapper.updateById(update);
            }
        }

        ExternalDeliveryResult externalDelivery = publishExternalDeliveryIfNecessary(instance, nodeExec, nodeConfig, payload);
        if (externalDelivery != null) {
            artifact.putAll(externalDelivery.artifactData());
            deliveryTargets.add(externalDelivery.deliveryTarget());
        }

        ArtifactService.WorkflowArtifactPersistResult persistedArtifact = artifactService.saveWorkflowArtifact(
                new ArtifactService.WorkflowArtifactPersistCommand(
                        firstNonBlank(getVariable(instance, "tenantId", null), instance != null ? instance.getTenantId() : null),
                        instance.getSpecId(),
                        instance.getId(),
                        getVariable(instance, "workspaceId", null),
                        nodeExec != null ? nodeExec.getNodeId() : null,
                        resolveArtifactTitle(instance, nodeExec, nodeConfig),
                        resolveArtifactTitle(instance, nodeExec, nodeConfig),
                        payload.artifactType(),
                        payload.format(),
                        payload.mimeType(),
                        payload.content(),
                        payload.metadata(),
                        deliveryTargets
                )
        );
        artifact.put("artifactId", persistedArtifact.artifactId());
        artifact.put("artifactVersion", persistedArtifact.versionNumber());

        return artifact;
    }

    private ExternalDeliveryResult publishExternalDeliveryIfNecessary(WorkflowInstance instance,
                                                                      WorkflowNodeExecution nodeExec,
                                                                      Map<String, Object> nodeConfig,
                                                                      ArtifactPayload payload) {
        Map<String, Object> deliveryConfig = resolveArtifactDeliveryConfig(instance, nodeConfig);
        String deliveryType = readConfig(deliveryConfig, "artifactDeliveryType");
        if (!"feishu_doc".equalsIgnoreCase(deliveryType)) {
            return null;
        }
        NotificationChannel channel = resolveArtifactDeliveryChannel(deliveryConfig);
        if (!"feishu".equalsIgnoreCase(channel.getChannelType())) {
            throw new BusinessException(ResultCode.NOTIFICATION_CHANNEL_NOT_FOUND,
                    "产物飞书文档投递仅支持飞书渠道，当前为: " + channel.getChannelType());
        }
        String deliveryTitle = firstNonBlank(
                readConfig(deliveryConfig, "artifactDeliveryTitle"),
                resolveArtifactTitle(instance, nodeExec, nodeConfig)
        );
        Map<String, Object> deliveryChannelConfig = buildArtifactDeliveryChannelConfig(channel.getConfig(), deliveryConfig);
        String deliveryMarkdown = buildArtifactDeliveryMarkdown(payload);
        FeishuDocDeliveryService.FeishuDocDeliveryResult deliveryResult =
                feishuDocDeliveryService.deliver(deliveryChannelConfig, deliveryTitle, deliveryMarkdown);

        Map<String, Object> artifactData = new LinkedHashMap<>();
        artifactData.put("artifactDeliveryType", "feishu_doc");
        artifactData.put("artifactDeliveryChannelId", channel.getId());
        artifactData.put("artifactDeliveryChannelName", channel.getName());
        artifactData.put("artifactDeliveryDocumentId", deliveryResult.documentId());
        artifactData.put("artifactDeliveryUrl", deliveryResult.documentUrl());
        artifactData.put("artifactDeliveryTitle", deliveryResult.title());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("documentId", deliveryResult.documentId());
        metadata.put("title", deliveryResult.title());
        metadata.put("channelId", channel.getId());
        metadata.put("channelName", channel.getName());
        metadata.put("revisionId", deliveryResult.revisionId());
        metadata.put("blockCount", deliveryResult.blockCount());

        ArtifactService.WorkspaceDeliveryTarget deliveryTarget = new ArtifactService.WorkspaceDeliveryTarget(
                null,
                null,
                deliveryResult.documentUrl(),
                "feishu_doc",
                "工作流已投递到飞书文档",
                metadata
        );
        return new ExternalDeliveryResult(artifactData, deliveryTarget);
    }

    private boolean shouldPersist(WorkflowInstance instance,
                                  WorkflowNodeExecution nodeExec,
                                  Map<String, Object> nodeConfig) {
        if (nodeExec == null || instance == null) {
            return false;
        }
        if (StringUtils.hasText(resolveArtifactDocType(instance, nodeExec, nodeConfig))) {
            return true;
        }
        if (isMarketingArtifactNode(instance, nodeConfig)) {
            return true;
        }
        if (StringUtils.hasText(readConfig(nodeConfig, "artifactOutputPath"))) {
            return true;
        }
        return "doc_gen".equalsIgnoreCase(nodeExec.getNodeId());
    }

    private NotificationChannel resolveArtifactDeliveryChannel(Map<String, Object> deliveryConfig) {
        String channelId = readConfig(deliveryConfig, "artifactDeliveryChannelId");
        if (StringUtils.hasText(channelId)) {
            NotificationChannel channel = notificationChannelMapper.selectById(channelId);
            if (channel == null || !CommonConstant.STATUS_ACTIVE.equals(channel.getStatus())) {
                throw new BusinessException(ResultCode.NOTIFICATION_CHANNEL_NOT_FOUND,
                        "未找到可用的产物交付渠道: " + channelId);
            }
            return channel;
        }
        String channelType = firstNonBlank(readConfig(deliveryConfig, "artifactDeliveryChannelType"), "feishu");
        NotificationChannel channel = notificationChannelMapper.selectOne(
                new LambdaQueryWrapper<NotificationChannel>()
                        .eq(NotificationChannel::getChannelType, channelType)
                        .eq(NotificationChannel::getStatus, CommonConstant.STATUS_ACTIVE)
                        .orderByDesc(NotificationChannel::getUpdatedAt)
                        .last("limit 1")
        );
        if (channel == null) {
            throw new BusinessException(ResultCode.NOTIFICATION_CHANNEL_NOT_FOUND,
                    "未找到已启用的产物交付渠道类型: " + channelType);
        }
        return channel;
    }

    private Map<String, Object> resolveArtifactDeliveryConfig(WorkflowInstance instance, Map<String, Object> nodeConfig) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        if (nodeConfig != null && !nodeConfig.isEmpty()) {
            resolved.putAll(nodeConfig);
        }
        Map<String, Object> profileDeliveryConfig = readProfileDeliveryConfig(instance);
        if (profileDeliveryConfig.isEmpty()) {
            return resolved;
        }
        overrideDeliveryConfig(resolved, "artifactDeliveryType",
                readConfig(profileDeliveryConfig, "artifactDeliveryType"),
                readConfig(profileDeliveryConfig, "deliveryType"));
        overrideDeliveryConfig(resolved, "artifactDeliveryChannelId",
                readConfig(profileDeliveryConfig, "artifactDeliveryChannelId"),
                readConfig(profileDeliveryConfig, "channelId"));
        overrideDeliveryConfig(resolved, "artifactDeliveryChannelType",
                readConfig(profileDeliveryConfig, "artifactDeliveryChannelType"),
                readConfig(profileDeliveryConfig, "channelType"));
        overrideDeliveryConfig(resolved, "artifactDeliveryTitle",
                readConfig(profileDeliveryConfig, "artifactDeliveryTitle"),
                readConfig(profileDeliveryConfig, "title"));
        overrideDeliveryConfig(resolved, "artifactDeliveryFolderToken",
                readConfig(profileDeliveryConfig, "artifactDeliveryFolderToken"),
                readConfig(profileDeliveryConfig, "documentFolderToken"),
                readConfig(profileDeliveryConfig, "folderToken"));
        return resolved;
    }

    private Map<String, Object> buildArtifactDeliveryChannelConfig(Map<String, Object> channelConfig,
                                                                   Map<String, Object> deliveryConfig) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        if (channelConfig != null && !channelConfig.isEmpty()) {
            resolved.putAll(channelConfig);
        }
        String folderToken = readConfig(deliveryConfig, "artifactDeliveryFolderToken");
        if (StringUtils.hasText(folderToken)) {
            resolved.put("document_folder_token", folderToken.trim());
        }
        return resolved;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readProfileDeliveryConfig(WorkflowInstance instance) {
        if (instance == null || instance.getVariables() == null) {
            return Map.of();
        }
        Object profileData = instance.getVariables().get("profileData");
        if (!(profileData instanceof Map<?, ?> profileMap)) {
            return Map.of();
        }
        Object deliveryConfig = ((Map<String, Object>) profileMap).get("deliveryConfig");
        if (!(deliveryConfig instanceof Map<?, ?> deliveryMap) || deliveryMap.isEmpty()) {
            return Map.of();
        }
        return new LinkedHashMap<>((Map<String, Object>) deliveryMap);
    }

    private void overrideDeliveryConfig(Map<String, Object> target, String key, String... candidates) {
        if (target == null || !StringUtils.hasText(key) || candidates == null) {
            return;
        }
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate)) {
                target.put(key, candidate.trim());
                return;
            }
        }
    }

    private String resolveArtifactDocType(WorkflowInstance instance,
                                          WorkflowNodeExecution nodeExec,
                                          Map<String, Object> nodeConfig) {
        String fromConfig = readConfig(nodeConfig, "artifactDocType");
        if (StringUtils.hasText(fromConfig)) {
            return fromConfig.trim();
        }
        String inferredDocType = ArtifactSceneResolver.inferArtifactDocType(nodeConfig);
        if (StringUtils.hasText(inferredDocType)) {
            return inferredDocType;
        }
        if (nodeExec != null && "doc_gen".equalsIgnoreCase(nodeExec.getNodeId())) {
            return getVariable(instance, "artifactDocType", null);
        }
        return null;
    }

    private String resolveArtifactOutputPath(WorkflowInstance instance,
                                             WorkflowNodeExecution nodeExec,
                                             Map<String, Object> nodeConfig,
                                             String declaredArtifactOutputPath) {
        String fromConfig = readConfig(nodeConfig, "artifactOutputPath");
        if (StringUtils.hasText(fromConfig)) {
            return fromConfig.trim();
        }
        if (StringUtils.hasText(declaredArtifactOutputPath)) {
            return declaredArtifactOutputPath.trim();
        }
        if (shouldUseInheritedArtifactOutputPath(instance, nodeExec, nodeConfig)) {
            String inheritedPath = getVariable(instance, "artifactOutputPath", null);
            if (StringUtils.hasText(inheritedPath)) {
                return inheritedPath;
            }
        }
        String artifactDocType = resolveArtifactDocType(instance, nodeExec, nodeConfig);
        if (StringUtils.hasText(artifactDocType)) {
            String inferredPath = buildDefaultArtifactOutputPath(instance, artifactDocType);
            if (StringUtils.hasText(inferredPath)) {
                return inferredPath;
            }
        }
        if (!shouldUseInheritedArtifactOutputPath(instance, nodeExec, nodeConfig)) {
            return null;
        }
        return null;
    }

    private String buildDefaultArtifactOutputPath(WorkflowInstance instance, String artifactDocType) {
        if (!StringUtils.hasText(artifactDocType)) {
            return null;
        }
        String fileKey = resolveArtifactFileKey(instance);
        return switch (artifactDocType.trim()) {
            case "requirements" -> "docs/" + fileKey + "-requirements.md";
            case "design" -> "docs/" + fileKey + "-technical-design.md";
            case "tasks" -> "docs/" + fileKey + "-task-breakdown.md";
            case "implementation" -> "docs/" + fileKey + "-implementation.md";
            case "test_plan" -> "docs/" + fileKey + "-test-plan.md";
            case "delivery" -> "docs/" + fileKey + "-delivery.md";
            default -> null;
        };
    }

    private String resolveArtifactFileKey(WorkflowInstance instance) {
        String jiraTicket = getVariable(instance, "jiraTicket", null);
        if (StringUtils.hasText(jiraTicket)) {
            return jiraTicket.trim();
        }
        String specName = getVariable(instance, "specName", null);
        if (StringUtils.hasText(specName)) {
            String sanitized = specName.trim().toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9._-]+", "-")
                    .replaceAll("-{2,}", "-")
                    .replaceAll("^-|-$", "");
            if (StringUtils.hasText(sanitized)) {
                return sanitized;
            }
        }
        return "spec";
    }

    private String normalizeMarkdown(WorkflowInstance instance,
                                     WorkflowNodeExecution nodeExec,
                                     Map<String, Object> nodeConfig,
                                     String content) {
        if (!StringUtils.hasText(content)) {
            return content;
        }
        String trimmed = content.trim();
        if (hasPrimaryHeading(trimmed)) {
            return trimmed + System.lineSeparator();
        }

        String raw = unwrapMarkdownFence(trimmed);
        if (!StringUtils.hasText(raw)) {
            return raw;
        }
        if (hasPrimaryHeading(raw)) {
            return raw.trim() + System.lineSeparator();
        }

        String title = resolveArtifactTitle(instance, nodeExec, nodeConfig);
        return "# " + title + System.lineSeparator() + System.lineSeparator()
                + raw.trim() + System.lineSeparator();
    }

    private boolean hasPrimaryHeading(String markdown) {
        if (!StringUtils.hasText(markdown)) {
            return false;
        }
        String firstLine = markdown.stripLeading();
        int lineBreak = firstLine.indexOf('\n');
        if (lineBreak >= 0) {
            firstLine = firstLine.substring(0, lineBreak);
        }
        return firstLine.startsWith("# ");
    }

    private String sanitizeArtifactContent(String content) {
        if (!StringUtils.hasText(content)) {
            return content;
        }
        String sanitized = XML_TOOL_CALL_BLOCK_PATTERN.matcher(content).replaceAll("");
        sanitized = XML_TOOL_INVOKE_BLOCK_PATTERN.matcher(sanitized).replaceAll("");
        sanitized = XML_TOOL_TAG_PATTERN.matcher(sanitized).replaceAll("");
        sanitized = ABSOLUTE_WORKSPACE_PATH_PATTERN.matcher(sanitized).replaceAll("仓库工作区路径");
        sanitized = sanitized.replaceAll("(?m)^\\s*工具执行记录\\s*$", "");
        sanitized = sanitized.replaceAll("(?m)^\\s*<[^>]+>\\s*$", "");
        sanitized = sanitized.replaceAll("\\n{3,}", "\n\n").trim();
        sanitized = stripLeadingNarrativeBeforePrimaryHeading(sanitized);
        if (!StringUtils.hasText(sanitized)) {
            return null;
        }
        return sanitized + System.lineSeparator();
    }

    private String stripLeadingNarrativeBeforePrimaryHeading(String content) {
        if (!StringUtils.hasText(content)) {
            return content;
        }
        String normalized = content.trim();
        if (hasPrimaryHeading(normalized)) {
            return normalized;
        }
        Matcher matcher = Pattern.compile("(?m)^#\\s+").matcher(normalized);
        if (!matcher.find()) {
            return normalized;
        }
        String leadingBlock = normalized.substring(0, matcher.start()).trim();
        if (!StringUtils.hasText(leadingBlock)) {
            return normalized.substring(matcher.start()).trim();
        }
        if (leadingBlock.length() > 600) {
            return normalized;
        }
        return normalized.substring(matcher.start()).trim();
    }

    private String resolveArtifactTitle(WorkflowInstance instance,
                                        WorkflowNodeExecution nodeExec,
                                        Map<String, Object> nodeConfig) {
        String configuredTitle = readConfig(nodeConfig, "artifactTitle");
        if (StringUtils.hasText(configuredTitle)) {
            return configuredTitle.trim();
        }
        if (nodeExec != null && StringUtils.hasText(nodeExec.getNodeLabel())) {
            return nodeExec.getNodeLabel().trim();
        }
        String title = getVariable(instance, "specName", "技术文档");
        return title + " 技术文档";
    }

    private String buildArtifactDeliveryMarkdown(ArtifactPayload payload) {
        if (payload == null || !StringUtils.hasText(payload.content())) {
            return "";
        }
        if (!ARTIFACT_TYPE_MARKETING_BUNDLE.equalsIgnoreCase(payload.artifactType())) {
            return payload.content();
        }
        String summaryMarkdown = asText(payload.metadata().get("bundleSummaryMarkdown"));
        if (!StringUtils.hasText(summaryMarkdown)) {
            return payload.content();
        }
        String body = stripPrimaryHeading(defaultIfBlank(payload.content(), "")).trim();
        if (!StringUtils.hasText(body)) {
            return summaryMarkdown;
        }
        return summaryMarkdown
                + System.lineSeparator()
                + System.lineSeparator()
                + "---"
                + System.lineSeparator()
                + System.lineSeparator()
                + "## 文案详情"
                + System.lineSeparator()
                + System.lineSeparator()
                + body
                + System.lineSeparator();
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
        Matcher matcher = MARKDOWN_BLOCK_PATTERN.matcher(trimmed);
        while (matcher.find()) {
            String markdown = matcher.group(1);
            if (StringUtils.hasText(markdown)) {
                return markdown.trim();
            }
        }
        Matcher genericMatcher = GENERIC_FENCE_PATTERN.matcher(trimmed);
        if (genericMatcher.find()) {
            String markdown = genericMatcher.group(1);
            if (StringUtils.hasText(markdown)
                    && markdown.stripLeading().startsWith("#")
                    && !genericMatcher.find()) {
                return markdown.trim();
            }
        }
        return trimmed;
    }

    private String unwrapJsonFence(String content) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        String trimmed = content.trim();
        Matcher matcher = JSON_BLOCK_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }
        Matcher genericMatcher = GENERIC_FENCE_PATTERN.matcher(trimmed);
        if (genericMatcher.find()) {
            String candidate = genericMatcher.group(1).trim();
            if (candidate.startsWith("{") || candidate.startsWith("[")) {
                return candidate;
            }
        }
        return trimmed;
    }

    private String loadExistingArtifactMarkdown(String workspacePath,
                                               String artifactOutputPath,
                                               String result) {
        if (!StringUtils.hasText(workspacePath) || !StringUtils.hasText(artifactOutputPath)) {
            return null;
        }
        String declaredOutputPath = extractDeclaredArtifactPath(result);
        if (StringUtils.hasText(declaredOutputPath)
                && !artifactOutputPath.equals(declaredOutputPath.trim())) {
            return null;
        }
        Path absolutePath = resolveExistingArtifactPath(workspacePath, artifactOutputPath);
        if (absolutePath == null) {
            return null;
        }
        try {
            String content = Files.readString(absolutePath, StandardCharsets.UTF_8);
            if (!StringUtils.hasText(content)) {
                return null;
            }
            return content.trim() + System.lineSeparator();
        } catch (IOException e) {
            log.warn("读取已生成的工作区文档失败: path={}, error={}", artifactOutputPath, e.getMessage());
            return null;
        }
    }

    private String extractDeclaredArtifactPath(String result) {
        if (!StringUtils.hasText(result)) {
            return null;
        }
        Matcher matcher = OUTPUT_PATH_PATTERN.matcher(result);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private Path resolveExistingArtifactPath(String workspacePath, String artifactOutputPath) {
        if (!StringUtils.hasText(workspacePath) || !StringUtils.hasText(artifactOutputPath)) {
            return null;
        }
        Path absolutePath = Path.of(workspacePath).resolve(artifactOutputPath).normalize();
        if (Files.isDirectory(absolutePath)) {
            absolutePath = absolutePath.resolve("README.md");
        }
        if (!Files.exists(absolutePath) || !Files.isRegularFile(absolutePath)) {
            return null;
        }
        return absolutePath;
    }

    private SpecDocument saveSpecDocument(String specId, String docType, String content, String workflowNodeId) {
        SpecDocumentRequest request = new SpecDocumentRequest();
        request.setContent(content);
        request.setChangeSummary("工作流自动生成文档");
        return specVersionHandler.saveDocument(specId, docType, workflowNodeId, request);
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

    private List<ArtifactFileWriteResult> writeArtifactFiles(String workspacePath, List<ArtifactFile> files) {
        List<ArtifactFileWriteResult> results = new ArrayList<>();
        for (ArtifactFile file : files) {
            Path absolutePath = writeArtifactFile(workspacePath, file.relativePath(), file.content());
            results.add(new ArtifactFileWriteResult(file.relativePath(), absolutePath));
        }
        return results;
    }

    private void commitArtifact(String workspacePath, List<String> artifactOutputPaths, WorkflowInstance instance) {
        if (artifactOutputPaths == null || artifactOutputPaths.isEmpty()) {
            return;
        }
        try {
            String ticket = getVariable(instance, "jiraTicket", null);
            String message = StringUtils.hasText(ticket)
                    ? "docs: generate workflow artifact for " + ticket
                    : "docs: generate workflow artifact";
            gitOperationService.commitChanges(workspacePath, message, artifactOutputPaths);
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

    private Integer resolveExpectedDocVersion(String specId, String docType, String workflowNodeId) {
        if (!StringUtils.hasText(specId) || !StringUtils.hasText(docType)) {
            return null;
        }
        SpecDocument existing = StringUtils.hasText(workflowNodeId)
                ? specVersionHandler.getDocumentByNode(specId, workflowNodeId)
                : specVersionHandler.getDocument(specId, docType);
        if (existing == null || existing.getVersion() == null) {
            return 1;
        }
        return existing.getVersion() + 1;
    }

    private String enrichArtifactContentWithRuntimeMetadata(String markdown,
                                                            WorkflowInstance instance,
                                                            WorkflowNodeExecution nodeExec,
                                                            Map<String, Object> nodeConfig,
                                                            String docType,
                                                            Integer docVersion,
                                                            Map<String, Object> runtimeMetadata) {
        String cleanedMarkdown = stripRuntimeMetadataBlock(markdown);
        if (ArtifactSceneResolver.isCustomerDelivery(nodeConfig)
                || "delivery".equalsIgnoreCase(defaultIfBlank(docType, ""))) {
            if (!StringUtils.hasText(cleanedMarkdown)) {
                return cleanedMarkdown;
            }
            return cleanedMarkdown.trim() + System.lineSeparator();
        }
        String metadataBlock = buildRuntimeMetadataBlock(instance, nodeExec, docType, docVersion, runtimeMetadata);
        if (!StringUtils.hasText(metadataBlock)) {
            return cleanedMarkdown;
        }
        String normalizedMarkdown = StringUtils.hasText(cleanedMarkdown) ? cleanedMarkdown.trim() : "";
        String titleLine;
        String body;
        if (hasPrimaryHeading(normalizedMarkdown)) {
            int firstBreak = normalizedMarkdown.indexOf('\n');
            if (firstBreak < 0) {
                titleLine = normalizedMarkdown;
                body = "";
            } else {
                titleLine = normalizedMarkdown.substring(0, firstBreak).trim();
                body = normalizedMarkdown.substring(firstBreak + 1).trim();
            }
        } else {
            titleLine = "# " + resolveArtifactTitle(instance, nodeExec, nodeConfig);
            body = normalizedMarkdown;
        }
        StringBuilder builder = new StringBuilder(titleLine)
                .append(System.lineSeparator())
                .append(System.lineSeparator())
                .append(metadataBlock);
        if (StringUtils.hasText(body)) {
            builder.append(System.lineSeparator())
                    .append(System.lineSeparator())
                    .append(body);
        }
        builder.append(System.lineSeparator());
        return builder.toString();
    }

    private String buildRuntimeMetadataBlock(WorkflowInstance instance,
                                             WorkflowNodeExecution nodeExec,
                                             String docType,
                                             Integer docVersion,
                                             Map<String, Object> runtimeMetadata) {
        Map<String, String> entries = new java.util.LinkedHashMap<>();
        putMetadata(entries, "Spec ID", instance != null ? instance.getSpecId() : null);
        putMetadata(entries, "工作流实例 ID", instance != null ? instance.getId() : null);
        putMetadata(entries, "工作流节点", nodeExec != null ? nodeExec.getNodeId() : null);
        putMetadata(entries, "模型名", readMetadataValue(runtimeMetadata, "agentModel"));
        putMetadata(entries, "执行 ID", readMetadataValue(runtimeMetadata, "agentExecutionId"));
        putMetadata(entries, "运行时引擎", readMetadataValue(runtimeMetadata, "runtimeEngine"));
        putMetadata(entries, "文档类型", docType);
        putMetadata(entries, "文档版本", docVersion == null ? null : "v" + docVersion);
        putMetadata(entries, "质量分", readMetadataValue(runtimeMetadata, "qualityScore"));
        putMetadata(entries, "质量任务 ID", readMetadataValue(runtimeMetadata, "qualityTaskId"));
        putMetadata(entries, "质量检查时间", readMetadataValue(runtimeMetadata, "qualityCheckedAt"));
        if (entries.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder(RUNTIME_METADATA_TITLE)
                .append(System.lineSeparator())
                .append(System.lineSeparator())
                .append("| 字段 | 值 |").append(System.lineSeparator())
                .append("| --- | --- |").append(System.lineSeparator());
        entries.forEach((key, value) -> builder.append("| ")
                .append(key)
                .append(" | ")
                .append(value.replace("|", "\\|"))
                .append(" |")
                .append(System.lineSeparator()));
        builder.append(System.lineSeparator())
                .append("---");
        return builder.toString();
    }

    private void putMetadata(Map<String, String> target, String label, String value) {
        if (target == null || !StringUtils.hasText(label)) {
            return;
        }
        if (StringUtils.hasText(value)) {
            target.put(label, value.trim());
        }
    }

    private String readMetadataValue(Map<String, Object> runtimeMetadata, String key) {
        if (runtimeMetadata == null || !runtimeMetadata.containsKey(key) || runtimeMetadata.get(key) == null) {
            return null;
        }
        return String.valueOf(runtimeMetadata.get(key));
    }

    private String stripRuntimeMetadataBlock(String markdown) {
        if (!StringUtils.hasText(markdown) || !markdown.contains(RUNTIME_METADATA_TITLE)) {
            return markdown;
        }
        return markdown.replaceFirst("(?s)\\n{2}" + Pattern.quote(RUNTIME_METADATA_TITLE) + ".*?\\n---\\n*", "\n\n").trim()
                + System.lineSeparator();
    }

    private String readConfig(Map<String, Object> nodeConfig, String key) {
        if (nodeConfig == null) {
            return null;
        }
        Object value = nodeConfig.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private boolean shouldUseInheritedArtifactOutputPath(WorkflowInstance instance,
                                                         WorkflowNodeExecution nodeExec,
                                                         Map<String, Object> nodeConfig) {
        if (isMarketingArtifactNode(instance, nodeConfig)) {
            return true;
        }
        return nodeExec != null && "doc_gen".equalsIgnoreCase(nodeExec.getNodeId());
    }

    private boolean isMarketingArtifactNode(WorkflowInstance instance, Map<String, Object> nodeConfig) {
        return ARTIFACT_TYPE_MARKETING_BUNDLE.equalsIgnoreCase(resolveArtifactType(instance, nodeConfig));
    }

    private ArtifactPayload resolveArtifactPayload(WorkflowInstance instance,
                                                   WorkflowNodeExecution nodeExec,
                                                   Map<String, Object> nodeConfig,
                                                   String result,
                                                   String artifactOutputPath,
                                                   String workspacePath) {
        String artifactType = resolveArtifactType(instance, nodeConfig);
        if (ARTIFACT_TYPE_MARKETING_BUNDLE.equalsIgnoreCase(artifactType)) {
            return buildMarketingBundlePayload(instance, nodeExec, nodeConfig, result, artifactOutputPath);
        }

        String markdown = StringUtils.hasText(artifactOutputPath)
                ? loadExistingArtifactMarkdown(workspacePath, artifactOutputPath, result)
                : null;
        String fallbackMarkdown = null;
        if (!StringUtils.hasText(markdown)) {
            markdown = normalizeMarkdown(instance, nodeExec, nodeConfig, result);
        } else {
            fallbackMarkdown = normalizeMarkdown(instance, nodeExec, nodeConfig, result);
        }
        markdown = sanitizeArtifactContent(markdown);
        if (!StringUtils.hasText(markdown) && StringUtils.hasText(fallbackMarkdown)) {
            markdown = sanitizeArtifactContent(fallbackMarkdown);
        }

        List<ArtifactFile> files = new ArrayList<>();
        if (StringUtils.hasText(artifactOutputPath) && StringUtils.hasText(markdown)) {
            files.add(new ArtifactFile(artifactOutputPath, markdown));
        }
        return new ArtifactPayload("document", "markdown", "text/markdown", markdown, Map.of(), files);
    }

    private String resolveArtifactType(WorkflowInstance instance, Map<String, Object> nodeConfig) {
        String artifactType = readConfig(nodeConfig, "artifactType");
        if (StringUtils.hasText(artifactType)) {
            return artifactType.trim();
        }
        String specType = getVariable(instance, "specType", null);
        if ("marketing".equalsIgnoreCase(specType)) {
            return ARTIFACT_TYPE_MARKETING_BUNDLE;
        }
        return "document";
    }

    @SuppressWarnings("unchecked")
    private ArtifactPayload buildMarketingBundlePayload(WorkflowInstance instance,
                                                        WorkflowNodeExecution nodeExec,
                                                        Map<String, Object> nodeConfig,
                                                        String result,
                                                        String artifactOutputPath) {
        Map<String, Object> parsedJson = parseStructuredJson(result);
        List<Map<String, Object>> bundleItems = new ArrayList<>();
        Object variantSource = parsedJson.get("variants");
        if (!(variantSource instanceof List<?>)) {
            variantSource = parsedJson.get("bundleItems");
        }
        if (!(variantSource instanceof List<?>)) {
            variantSource = parsedJson.get("copies");
        }
        if (variantSource instanceof List<?> variants) {
            int index = 0;
            for (Object item : variants) {
                index++;
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                Map<String, Object> variant = new LinkedHashMap<>((Map<String, Object>) map);
                String variantKey = defaultIfBlank(asText(variant.get("variantKey")), String.valueOf((char) ('A' + index - 1)));
                String title = defaultIfBlank(
                        firstNonBlank(asText(variant.get("title")), asText(variant.get("headline")), asText(variant.get("name"))),
                        "营销文案变体 " + variantKey
                );
                String content = firstNonBlank(asText(variant.get("content")), asText(variant.get("copy")), asText(variant.get("markdown")));
                content = normalizeMarketingVariantContent(title, content);
                bundleItems.add(Map.of(
                        "variantKey", variantKey,
                        "title", title,
                        "fileName", "variant-" + variantKey.toLowerCase(Locale.ROOT) + ".md",
                        "content", content
                ));
            }
        }
        if (bundleItems.isEmpty()) {
            String fallbackContent = normalizeMarketingVariantContent(resolveArtifactTitle(instance, nodeExec, nodeConfig), sanitizeArtifactContent(result));
            bundleItems.add(Map.of(
                    "variantKey", "A",
                    "title", resolveArtifactTitle(instance, nodeExec, nodeConfig),
                    "fileName", "variant-a.md",
                    "content", fallbackContent
            ));
        }

        Map<String, Object> metadata = new LinkedHashMap<>(parsedJson);
        String summaryMarkdown = buildMarketingSummaryMarkdown(instance, nodeExec, nodeConfig, parsedJson, bundleItems);
        metadata.put("bundleItems", bundleItems);
        metadata.put("bundleSummaryMarkdown", summaryMarkdown);
        metadata.put("specType", "marketing");

        String primaryMarkdown = buildMarketingPrimaryMarkdown(
                resolveArtifactTitle(instance, nodeExec, nodeConfig),
                firstNonBlank(asText(parsedJson.get("summary")), asText(parsedJson.get("brief"))),
                bundleItems
        );
        String baseDir = normalizeMarketingBaseDir(artifactOutputPath, instance);
        List<ArtifactFile> files = new ArrayList<>();
        for (Map<String, Object> item : bundleItems) {
            files.add(new ArtifactFile(baseDir + asText(item.get("fileName")), asText(item.get("content"))));
        }
        files.add(new ArtifactFile(baseDir + "README.md", summaryMarkdown));
        return new ArtifactPayload(ARTIFACT_TYPE_MARKETING_BUNDLE, "markdown", "text/markdown", primaryMarkdown, metadata, files);
    }

    private Map<String, Object> parseStructuredJson(String result) {
        String jsonContent = unwrapJsonFence(result);
        if (!StringUtils.hasText(jsonContent)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(jsonContent, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            log.warn("营销产物解析 structured_json 失败，将回退为单文档模式: {}", ex.getMessage());
            return Map.of();
        }
    }

    private String buildMarketingSummaryMarkdown(WorkflowInstance instance,
                                                 WorkflowNodeExecution nodeExec,
                                                 Map<String, Object> nodeConfig,
                                                 Map<String, Object> parsedJson,
                                                 List<Map<String, Object>> bundleItems) {
        String title = resolveArtifactTitle(instance, nodeExec, nodeConfig);
        String summary = firstNonBlank(asText(parsedJson.get("summary")), asText(parsedJson.get("brief")), "已生成营销文案产物包。");
        StringBuilder builder = new StringBuilder("# ")
                .append(title)
                .append(System.lineSeparator())
                .append(System.lineSeparator())
                .append("## 产物概览")
                .append(System.lineSeparator())
                .append(System.lineSeparator())
                .append(summary)
                .append(System.lineSeparator())
                .append(System.lineSeparator())
                .append("## AB 变体")
                .append(System.lineSeparator())
                .append(System.lineSeparator());
        for (Map<String, Object> item : bundleItems) {
            builder.append("- ")
                    .append(asText(item.get("variantKey")))
                    .append(": ")
                    .append(asText(item.get("title")))
                    .append(System.lineSeparator());
        }
        String compliance = asText(parsedJson.get("complianceNotes"));
        if (StringUtils.hasText(compliance)) {
            builder.append(System.lineSeparator())
                    .append("## 检查说明")
                    .append(System.lineSeparator())
                    .append(System.lineSeparator())
                    .append(compliance)
                    .append(System.lineSeparator());
        }
        return builder.toString();
    }

    private String buildMarketingPrimaryMarkdown(String title,
                                                 String summary,
                                                 List<Map<String, Object>> bundleItems) {
        if (bundleItems == null || bundleItems.isEmpty()) {
            return "# " + defaultIfBlank(title, "营销文案交付包") + System.lineSeparator();
        }
        if (bundleItems.size() == 1) {
            return defaultIfBlank(asText(bundleItems.getFirst().get("content")), "");
        }
        StringBuilder builder = new StringBuilder("# ")
                .append(defaultIfBlank(title, "营销文案交付包"))
                .append(System.lineSeparator())
                .append(System.lineSeparator());
        if (StringUtils.hasText(summary)) {
            builder.append("## 交付概览")
                    .append(System.lineSeparator())
                    .append(System.lineSeparator())
                    .append(summary.trim())
                    .append(System.lineSeparator())
                    .append(System.lineSeparator())
                    .append("---")
                    .append(System.lineSeparator())
                    .append(System.lineSeparator());
        }
        for (int index = 0; index < bundleItems.size(); index++) {
            Map<String, Object> item = bundleItems.get(index);
            builder.append("## 变体 ")
                    .append(defaultIfBlank(asText(item.get("variantKey")), String.valueOf((char) ('A' + index))))
                    .append("：")
                    .append(defaultIfBlank(asText(item.get("title")), "营销文案变体"))
                    .append(System.lineSeparator())
                    .append(System.lineSeparator())
                    .append(stripPrimaryHeading(defaultIfBlank(asText(item.get("content")), "暂无内容")).trim())
                    .append(System.lineSeparator());
            if (index < bundleItems.size() - 1) {
                builder.append(System.lineSeparator())
                        .append("---")
                        .append(System.lineSeparator())
                        .append(System.lineSeparator());
            }
        }
        return builder.toString();
    }

    private String normalizeMarketingBaseDir(String artifactOutputPath, WorkflowInstance instance) {
        String baseDir = StringUtils.hasText(artifactOutputPath) ? artifactOutputPath.trim() : null;
        if (!StringUtils.hasText(baseDir)) {
            String specName = getVariable(instance, "specName", "marketing-artifact");
            baseDir = "marketing/" + sanitizeFileSegment(specName) + "/";
        }
        if (baseDir.endsWith(".md")) {
            int lastSlash = baseDir.lastIndexOf('/') + 1;
            baseDir = baseDir.substring(0, lastSlash);
        }
        return baseDir.endsWith("/") ? baseDir : baseDir + "/";
    }

    private String normalizeMarketingVariantContent(String title, String content) {
        String sanitized = sanitizeArtifactContent(content);
        if (!StringUtils.hasText(sanitized)) {
            sanitized = "暂无内容";
        }
        sanitized = stripMarketingLeadWrapper(sanitized);
        if (hasPrimaryHeading(sanitized)) {
            return sanitized;
        }
        return "# " + title + System.lineSeparator() + System.lineSeparator() + sanitized;
    }

    private String stripMarketingLeadWrapper(String markdown) {
        if (!StringUtils.hasText(markdown)) {
            return markdown;
        }
        String normalized = markdown.trim();
        Matcher matcher = Pattern.compile("(?m)^#\\s+").matcher(normalized);
        if (!matcher.find() || !matcher.find()) {
            return normalized;
        }
        int secondHeadingStart = matcher.start();
        String wrapper = normalized.substring(0, secondHeadingStart).trim();
        String wrapperLower = wrapper.toLowerCase(Locale.ROOT);
        boolean hasLeadNarration = wrapperLower.contains("based on the upstream")
                || wrapperLower.contains("team leader")
                || wrapper.contains("我将")
                || wrapper.contains("汇总")
                || wrapper.contains("交付最终");
        boolean looksLikeShortWrapper = wrapper.length() <= 600
                && wrapper.contains("---")
                && !wrapper.contains("\n## ")
                && !wrapper.contains("\n### ");
        if (!hasLeadNarration && !looksLikeShortWrapper) {
            return normalized;
        }
        String stripped = normalized.substring(secondHeadingStart).trim();
        return StringUtils.hasText(stripped) ? stripped : normalized;
    }

    private String stripPrimaryHeading(String markdown) {
        if (!StringUtils.hasText(markdown)) {
            return "";
        }
        String trimmed = markdown.trim();
        if (!hasPrimaryHeading(trimmed)) {
            return trimmed;
        }
        int firstBreak = trimmed.indexOf('\n');
        if (firstBreak < 0) {
            return "";
        }
        return trimmed.substring(firstBreak + 1).trim();
    }

    private String sanitizeFileSegment(String value) {
        String raw = StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "artifact";
        String sanitized = raw.replaceAll("[^a-z0-9._-]+", "-").replaceAll("-{2,}", "-");
        sanitized = sanitized.replaceAll("^-|-$", "");
        return StringUtils.hasText(sanitized) ? sanitized : "artifact";
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String defaultIfBlank(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private record ArtifactPayload(
            String artifactType,
            String format,
            String mimeType,
            String content,
            Map<String, Object> metadata,
            List<ArtifactFile> files
    ) {
        private ArtifactPayload withContent(String newContent) {
            if (files == null || files.isEmpty()) {
                return new ArtifactPayload(artifactType, format, mimeType, newContent, metadata, files);
            }
            List<ArtifactFile> updatedFiles = new ArrayList<>(files);
            ArtifactFile primaryFile = updatedFiles.get(0);
            updatedFiles.set(0, new ArtifactFile(primaryFile.relativePath(), newContent));
            return new ArtifactPayload(artifactType, format, mimeType, newContent, metadata, updatedFiles);
        }
    }

    private record ExternalDeliveryResult(
            Map<String, Object> artifactData,
            ArtifactService.WorkspaceDeliveryTarget deliveryTarget
    ) {
    }

    private record ArtifactFile(
            String relativePath,
            String content
    ) {
    }

    private record ArtifactFileWriteResult(
            String relativePath,
            Path absolutePath
    ) {
    }
}
