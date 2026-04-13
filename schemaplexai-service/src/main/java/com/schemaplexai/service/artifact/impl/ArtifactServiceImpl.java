package com.schemaplexai.service.artifact.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.ArtifactDeliveryMapper;
import com.schemaplexai.dao.mapper.ArtifactMapper;
import com.schemaplexai.dao.mapper.ArtifactVersionMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.model.entity.Artifact;
import com.schemaplexai.model.entity.ArtifactDelivery;
import com.schemaplexai.model.entity.ArtifactVersion;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.vo.artifact.ArtifactDeliveryVO;
import com.schemaplexai.model.vo.artifact.ArtifactVO;
import com.schemaplexai.model.vo.artifact.ArtifactVersionVO;
import com.schemaplexai.service.artifact.ArtifactService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 统一产物服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArtifactServiceImpl implements ArtifactService {

    private static final String ARTIFACT_TYPE_MARKETING_BUNDLE = "marketing_copy_bundle";
    private static final String FORMAT_MARKDOWN = "markdown";
    private static final String MIME_MARKDOWN = "text/markdown";

    private final ArtifactMapper artifactMapper;
    private final ArtifactVersionMapper artifactVersionMapper;
    private final ArtifactDeliveryMapper artifactDeliveryMapper;
    private final SpecMapper specMapper;

    @Override
    public ArtifactVO getById(String id) {
        Artifact artifact = artifactMapper.selectById(id);
        if (artifact == null) {
            throw new BusinessException(ResultCode.ARTIFACT_NOT_FOUND);
        }
        return toDetailVO(artifact);
    }

    @Override
    public List<ArtifactVO> listByWorkspaceId(String workspaceId) {
        return artifactMapper.selectList(new LambdaQueryWrapper<Artifact>()
                        .eq(Artifact::getWorkspaceId, workspaceId)
                        .orderByDesc(Artifact::getUpdatedAt))
                .stream()
                .map(this::toListVO)
                .toList();
    }

    @Override
    public List<ArtifactVO> listBySpecId(String specId) {
        return artifactMapper.selectList(new LambdaQueryWrapper<Artifact>()
                        .eq(Artifact::getSpecId, specId)
                        .orderByDesc(Artifact::getUpdatedAt))
                .stream()
                .map(this::toListVO)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkflowArtifactPersistResult saveWorkflowArtifact(WorkflowArtifactPersistCommand command) {
        if (command == null || !StringUtils.hasText(command.contentText())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "产物内容不能为空");
        }
        Spec spec = StringUtils.hasText(command.specId()) ? specMapper.selectById(command.specId()) : null;
        Artifact artifact = resolveArtifactForWrite(spec);
        boolean creating = artifact == null;
        if (creating) {
            artifact = new Artifact();
            artifact.setTenantId(resolveTenantId(command, spec));
            artifact.setSpecId(command.specId());
            artifact.setWorkflowInstanceId(command.workflowInstanceId());
            artifact.setWorkspaceId(command.workspaceId());
            artifact.setLatestVersion(0);
        }
        artifact.setName(resolveName(command, spec));
        artifact.setTitle(resolveTitle(command, spec));
        artifact.setArtifactType(defaultIfBlank(command.artifactType(), "document"));
        artifact.setFormat(defaultIfBlank(command.format(), FORMAT_MARKDOWN));
        artifact.setMimeType(defaultIfBlank(command.mimeType(), MIME_MARKDOWN));
        artifact.setSourceNodeId(command.sourceNodeId());
        artifact.setContentText(command.contentText());
        artifact.setMetadataJson(command.metadataJson());
        artifact.setLatestVersion((artifact.getLatestVersion() == null ? 0 : artifact.getLatestVersion()) + 1);

        if (creating) {
            artifactMapper.insert(artifact);
        } else {
            artifactMapper.updateById(artifact);
        }

        ArtifactVersion version = new ArtifactVersion();
        version.setTenantId(artifact.getTenantId());
        version.setArtifactId(artifact.getId());
        version.setVersionNumber(artifact.getLatestVersion());
        version.setContentText(command.contentText());
        version.setMetadataJson(command.metadataJson());
        artifactVersionMapper.insert(version);

        if (!CollectionUtils.isEmpty(command.workspaceTargets())) {
            for (WorkspaceDeliveryTarget target : command.workspaceTargets()) {
                ArtifactDelivery delivery = new ArtifactDelivery();
                delivery.setTenantId(artifact.getTenantId());
                delivery.setArtifactId(artifact.getId());
                delivery.setWorkspaceId(defaultIfBlank(target.workspaceId(), command.workspaceId()));
                delivery.setDeliveryType(defaultIfBlank(target.deliveryType(), "workspace_file"));
                delivery.setTargetPath(target.targetPath());
                delivery.setTargetUri(target.targetUri());
                delivery.setDeliveryStatus("delivered");
                delivery.setMessage(target.message());
                delivery.setMetadataJson(target.metadataJson());
                delivery.setDeliveredAt(LocalDateTime.now());
                artifactDeliveryMapper.insert(delivery);
            }
        }

        if (spec != null && !StringUtils.hasText(spec.getPrimaryArtifactId())) {
            Spec update = new Spec();
            update.setId(spec.getId());
            update.setPrimaryArtifactId(artifact.getId());
            specMapper.updateById(update);
        } else if (spec != null && !Objects.equals(spec.getPrimaryArtifactId(), artifact.getId())) {
            Spec update = new Spec();
            update.setId(spec.getId());
            update.setPrimaryArtifactId(artifact.getId());
            specMapper.updateById(update);
        }

        return new WorkflowArtifactPersistResult(artifact.getId(), artifact.getLatestVersion());
    }

    @Override
    public DownloadPayload download(String id) {
        Artifact artifact = artifactMapper.selectById(id);
        if (artifact == null) {
            throw new BusinessException(ResultCode.ARTIFACT_NOT_FOUND);
        }
        if (ARTIFACT_TYPE_MARKETING_BUNDLE.equalsIgnoreCase(artifact.getArtifactType())) {
            return buildMarketingBundleDownload(artifact);
        }
        String extension = resolveExtension(artifact.getFormat());
        String fileName = sanitizeFileName(defaultIfBlank(artifact.getTitle(), artifact.getName())) + extension;
        return new DownloadPayload(fileName,
                defaultIfBlank(artifact.getMimeType(), MIME_MARKDOWN),
                defaultIfBlank(artifact.getContentText(), "").getBytes(StandardCharsets.UTF_8));
    }

    private Artifact resolveArtifactForWrite(Spec spec) {
        if (spec == null || !StringUtils.hasText(spec.getPrimaryArtifactId())) {
            return null;
        }
        return artifactMapper.selectById(spec.getPrimaryArtifactId());
    }

    private ArtifactVO toDetailVO(Artifact artifact) {
        ArtifactVO vo = toListVO(artifact);
        vo.setVersions(loadVersions(artifact.getId()));
        vo.setDeliveries(loadDeliveries(artifact.getId()));
        return vo;
    }

    private ArtifactVO toListVO(Artifact artifact) {
        ArtifactVO vo = new ArtifactVO();
        vo.setId(artifact.getId());
        vo.setSpecId(artifact.getSpecId());
        vo.setWorkflowInstanceId(artifact.getWorkflowInstanceId());
        vo.setWorkspaceId(artifact.getWorkspaceId());
        vo.setName(artifact.getName());
        vo.setTitle(artifact.getTitle());
        vo.setArtifactType(artifact.getArtifactType());
        vo.setFormat(artifact.getFormat());
        vo.setMimeType(artifact.getMimeType());
        vo.setLatestVersion(artifact.getLatestVersion());
        vo.setSourceNodeId(artifact.getSourceNodeId());
        vo.setContentText(resolveDisplayContent(artifact.getArtifactType(), artifact.getContentText(), artifact.getMetadataJson()));
        vo.setMetadataJson(artifact.getMetadataJson());
        vo.setCreatedAt(artifact.getCreatedAt());
        vo.setUpdatedAt(artifact.getUpdatedAt());
        return vo;
    }

    private List<ArtifactVersionVO> loadVersions(String artifactId) {
        return artifactVersionMapper.selectList(new LambdaQueryWrapper<ArtifactVersion>()
                        .eq(ArtifactVersion::getArtifactId, artifactId)
                        .orderByDesc(ArtifactVersion::getVersionNumber))
                .stream()
                .map(item -> {
                    ArtifactVersionVO vo = new ArtifactVersionVO();
                    vo.setId(item.getId());
                    vo.setVersionNumber(item.getVersionNumber());
                    vo.setContentText(item.getContentText());
                    vo.setMetadataJson(item.getMetadataJson());
                    vo.setCreatedAt(item.getCreatedAt());
                    vo.setCreatedBy(item.getCreatedBy());
                    return vo;
                })
                .toList();
    }

    private List<ArtifactDeliveryVO> loadDeliveries(String artifactId) {
        return artifactDeliveryMapper.selectList(new LambdaQueryWrapper<ArtifactDelivery>()
                        .eq(ArtifactDelivery::getArtifactId, artifactId)
                        .orderByDesc(ArtifactDelivery::getDeliveredAt)
                        .orderByDesc(ArtifactDelivery::getCreatedAt))
                .stream()
                .map(item -> {
                    ArtifactDeliveryVO vo = new ArtifactDeliveryVO();
                    vo.setId(item.getId());
                    vo.setWorkspaceId(item.getWorkspaceId());
                    vo.setDeliveryType(item.getDeliveryType());
                    vo.setTargetPath(item.getTargetPath());
                    vo.setTargetUri(item.getTargetUri());
                    vo.setDeliveryStatus(item.getDeliveryStatus());
                    vo.setMessage(item.getMessage());
                    vo.setDeliveredAt(item.getDeliveredAt());
                    vo.setMetadataJson(item.getMetadataJson());
                    vo.setCreatedAt(item.getCreatedAt());
                    return vo;
                })
                .toList();
    }

    private DownloadPayload buildMarketingBundleDownload(Artifact artifact) {
        List<Map<String, Object>> bundleItems = extractBundleItems(artifact.getMetadataJson());
        if (bundleItems.isEmpty()) {
            String fallbackContent = defaultIfBlank(resolveDisplayContent(
                    artifact.getArtifactType(),
                    artifact.getContentText(),
                    artifact.getMetadataJson()
            ), "");
            return new DownloadPayload(
                    sanitizeFileName(defaultIfBlank(artifact.getTitle(), artifact.getName())) + ".md",
                    MIME_MARKDOWN,
                    fallbackContent.getBytes(StandardCharsets.UTF_8)
            );
        }
        if (bundleItems.size() == 1) {
            Map<String, Object> item = bundleItems.getFirst();
            String fileName = defaultIfBlank(asText(item.get("fileName")),
                    sanitizeFileName(defaultIfBlank(asText(item.get("title")), defaultIfBlank(artifact.getTitle(), artifact.getName()))) + ".md");
            return new DownloadPayload(
                    fileName,
                    MIME_MARKDOWN,
                    defaultIfBlank(asText(item.get("content")), "").getBytes(StandardCharsets.UTF_8)
            );
        }
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            String rootDir = sanitizeFileName(defaultIfBlank(artifact.getTitle(), artifact.getName()));
            String summaryMarkdown = resolveBundleSummaryMarkdown(artifact.getContentText(), artifact.getMetadataJson());
            if (StringUtils.hasText(summaryMarkdown)) {
                addZipEntry(zipOutputStream, rootDir + "/README.md", summaryMarkdown);
            }
            for (Map<String, Object> item : bundleItems) {
                String fileName = defaultIfBlank(asText(item.get("fileName")),
                        sanitizeFileName(defaultIfBlank(asText(item.get("variantKey")), "variant")) + ".md");
                addZipEntry(zipOutputStream, rootDir + "/" + fileName, defaultIfBlank(asText(item.get("content")), ""));
            }
            zipOutputStream.finish();
            return new DownloadPayload(rootDir + ".zip", "application/zip", outputStream.toByteArray());
        } catch (IOException ex) {
            log.error("打包营销产物失败: artifactId={}", artifact.getId(), ex);
            throw new BusinessException(ResultCode.FAIL, "产物下载打包失败");
        }
    }

    private void addZipEntry(ZipOutputStream zipOutputStream, String path, String content) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        zipOutputStream.putNextEntry(entry);
        zipOutputStream.write(content.getBytes(StandardCharsets.UTF_8));
        zipOutputStream.closeEntry();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractBundleItems(Map<String, Object> metadataJson) {
        if (metadataJson == null) {
            return List.of();
        }
        Object bundleItems = metadataJson.get("bundleItems");
        if (!(bundleItems instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        result.sort(Comparator.comparing(item -> defaultIfBlank(asText(item.get("variantKey")), "z")));
        return result;
    }

    private String resolveDisplayContent(String artifactType, String contentText, Map<String, Object> metadataJson) {
        if (!ARTIFACT_TYPE_MARKETING_BUNDLE.equalsIgnoreCase(artifactType)) {
            return contentText;
        }
        List<Map<String, Object>> bundleItems = extractBundleItems(metadataJson);
        if (!bundleItems.isEmpty()) {
            String primaryContent = asText(bundleItems.getFirst().get("content"));
            if (StringUtils.hasText(primaryContent)) {
                return primaryContent;
            }
        }
        return contentText;
    }

    private String resolveBundleSummaryMarkdown(String contentText, Map<String, Object> metadataJson) {
        if (metadataJson != null) {
            Object summary = metadataJson.get("bundleSummaryMarkdown");
            if (summary != null && StringUtils.hasText(String.valueOf(summary))) {
                return String.valueOf(summary);
            }
        }
        return contentText;
    }

    private String resolveTenantId(WorkflowArtifactPersistCommand command, Spec spec) {
        if (StringUtils.hasText(command.tenantId())) {
            return command.tenantId();
        }
        if (spec != null && StringUtils.hasText(spec.getTenantId())) {
            return spec.getTenantId();
        }
        throw new BusinessException(ResultCode.BAD_REQUEST, "缺少租户信息");
    }

    private String resolveName(WorkflowArtifactPersistCommand command, Spec spec) {
        if (StringUtils.hasText(command.name())) {
            return command.name().trim();
        }
        if (StringUtils.hasText(command.title())) {
            return command.title().trim();
        }
        if (spec != null && StringUtils.hasText(spec.getName())) {
            return spec.getName().trim();
        }
        return "未命名产物";
    }

    private String resolveTitle(WorkflowArtifactPersistCommand command, Spec spec) {
        if (StringUtils.hasText(command.title())) {
            return command.title().trim();
        }
        return resolveName(command, spec);
    }

    private String resolveExtension(String format) {
        if (!StringUtils.hasText(format)) {
            return ".txt";
        }
        return switch (format.trim().toLowerCase(Locale.ROOT)) {
            case "markdown", "md" -> ".md";
            case "json" -> ".json";
            case "html" -> ".html";
            case "text", "txt" -> ".txt";
            default -> ".txt";
        };
    }

    private String sanitizeFileName(String value) {
        String sanitized = defaultIfBlank(value, "artifact")
                .replaceAll("[\\\\/:*?\"<>|]+", "-")
                .replaceAll("\\s+", "-")
                .replaceAll("-{2,}", "-");
        sanitized = sanitized.replaceAll("^-|-$", "");
        return StringUtils.hasText(sanitized) ? sanitized : "artifact";
    }

    private String defaultIfBlank(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
