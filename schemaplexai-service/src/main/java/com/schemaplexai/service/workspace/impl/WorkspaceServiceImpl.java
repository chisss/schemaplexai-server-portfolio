package com.schemaplexai.service.workspace.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.enums.WorkspaceSourceTypeEnum;
import com.schemaplexai.common.enums.WorkspaceStatusEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.WorkspaceMapper;
import com.schemaplexai.model.converter.WorkspaceConverter;
import com.schemaplexai.model.dto.workspace.WorkspaceCreateRequest;
import com.schemaplexai.model.dto.workspace.WorkspaceQueryRequest;
import com.schemaplexai.model.dto.workspace.WorkspaceUpdateRequest;
import com.schemaplexai.model.entity.Workspace;
import com.schemaplexai.model.vo.artifact.ArtifactVO;
import com.schemaplexai.model.vo.workspace.WorkspaceFileContentVO;
import com.schemaplexai.model.vo.workspace.WorkspaceFileVO;
import com.schemaplexai.model.vo.workspace.WorkspaceVO;
import com.schemaplexai.service.artifact.ArtifactService;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.integration.git.GitOperationService;
import com.schemaplexai.service.workspace.WorkspacePathResolver;
import com.schemaplexai.service.workspace.WorkspaceService;
import com.schemaplexai.service.workspace.validator.WorkspaceValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 工作空间服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceServiceImpl implements WorkspaceService {

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceConverter workspaceConverter;
    private final WorkspaceValidator workspaceValidator;
    private final EntityValidator entityValidator;
    private final WorkspacePathResolver workspacePathResolver;
    private final GitOperationService gitOperationService;
    private final ArtifactService artifactService;

    private static final long TEXT_PREVIEW_LIMIT = 512 * 1024L;

    @Override
    public WorkspaceVO create(WorkspaceCreateRequest request) {
        workspaceValidator.validateNameUnique(request.getName());

        String tenantId = requireTenantId();
        String sourceType = normalizeSourceType(request.getSourceType());
        validateCreateRequestBySource(sourceType, request);

        Workspace entity = workspaceConverter.fromCreateRequest(request);
        entity.setTenantId(tenantId);
        entity.setSourceType(sourceType);
        entity.setWorkspaceStatus(WorkspaceStatusEnum.READY.getCode());
        applyWorkspaceCapabilities(entity, sourceType);

        workspaceMapper.insert(entity);

        if (WorkspaceSourceTypeEnum.GIT.getCode().equals(sourceType)) {
            return createGitWorkspace(entity, request);
        }
        if (WorkspaceSourceTypeEnum.LOCAL.getCode().equals(sourceType)) {
            return createLocalWorkspace(entity, request);
        }
        return createManualWorkspace(entity);
    }

    @Override
    public PageResult<WorkspaceVO> page(WorkspaceQueryRequest request) {
        Page<Workspace> page = new Page<>(request.getPage(), request.getSize());
        LambdaQueryWrapper<Workspace> wrapper = new LambdaQueryWrapper<>();

        if (StringUtils.hasText(request.getSourceType())) {
            wrapper.eq(Workspace::getSourceType, request.getSourceType().trim().toLowerCase(Locale.ROOT));
        }
        if (StringUtils.hasText(request.getWorkspaceStatus())) {
            wrapper.eq(Workspace::getWorkspaceStatus, request.getWorkspaceStatus());
        }
        if (StringUtils.hasText(request.getGitPlatform())) {
            wrapper.eq(Workspace::getGitPlatform, request.getGitPlatform());
        }
        if (StringUtils.hasText(request.getKeyword())) {
            wrapper.and(w -> w.like(Workspace::getName, request.getKeyword())
                    .or().like(Workspace::getDescription, request.getKeyword())
                    .or().like(Workspace::getGitUrl, request.getKeyword()));
        }
        wrapper.orderByDesc(Workspace::getCreatedAt);

        var result = workspaceMapper.selectPage(page, wrapper);
        result.getRecords().forEach(this::refreshDiskUsage);
        return new PageResult<>(workspaceConverter.toVOList(result.getRecords()),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public WorkspaceVO getById(String id) {
        Workspace workspace = entityValidator.requireExists(workspaceMapper, id, ResultCode.WORKSPACE_NOT_FOUND);
        refreshDiskUsage(workspace);
        return workspaceConverter.toVO(workspace);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkspaceVO update(String id, WorkspaceUpdateRequest request) {
        Workspace workspace = entityValidator.requireExists(workspaceMapper, id, ResultCode.WORKSPACE_NOT_FOUND);

        if (StringUtils.hasText(request.getName()) && !request.getName().equals(workspace.getName())) {
            Long count = workspaceMapper.selectCount(new LambdaQueryWrapper<Workspace>()
                    .eq(Workspace::getName, request.getName())
                    .ne(Workspace::getId, id));
            if (count != null && count > 0) {
                throw new BusinessException(ResultCode.WORKSPACE_NAME_DUPLICATE);
            }
        }

        Workspace updateEntity = new Workspace();
        updateEntity.setId(id);
        if (StringUtils.hasText(request.getName())) {
            updateEntity.setName(request.getName());
        }
        if (StringUtils.hasText(request.getDefaultBranch())) {
            updateEntity.setDefaultBranch(request.getDefaultBranch());
        }
        if (request.getDescription() != null) {
            updateEntity.setDescription(request.getDescription());
        }
        if (request.getGitCredential() != null) {
            updateEntity.setGitCredential(request.getGitCredential());
        }
        workspaceMapper.updateById(updateEntity);

        log.info("更新工作空间成功: workspaceId={}", id);
        return getById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        entityValidator.requireExists(workspaceMapper, id, ResultCode.WORKSPACE_NOT_FOUND);
        workspaceMapper.deleteById(id);
        log.info("删除工作空间成功(仅数据库记录): workspaceId={}", id);
    }

    @Override
    public WorkspaceVO sync(String id) {
        Workspace workspace = entityValidator.requireExists(workspaceMapper, id, ResultCode.WORKSPACE_NOT_FOUND);
        String sourceType = normalizeSourceType(workspace.getSourceType());

        if (WorkspaceSourceTypeEnum.MANUAL.getCode().equals(sourceType)) {
            Path workspacePath = resolveAndValidateSyncPath(workspace);
            ensureDirectoryExists(workspacePath);
            long diskUsageMb = gitOperationService.calculateDiskUsageMb(workspacePath.toString());
            updateSyncStatus(id, WorkspaceStatusEnum.READY.getCode(), null, diskUsageMb, LocalDateTime.now());
            return getById(id);
        }

        Path localPath = resolveAndValidateSyncPath(workspace);
        updateSyncStatus(id, WorkspaceStatusEnum.SYNCING.getCode(), null, null, null);

        try {
            if (WorkspaceSourceTypeEnum.GIT.getCode().equals(sourceType)) {
                gitOperationService.pull(localPath.toString(), workspace.getGitCredential());
            }
            long diskUsageMb = gitOperationService.calculateDiskUsageMb(localPath.toString());
            updateSyncStatus(id, WorkspaceStatusEnum.READY.getCode(), null, diskUsageMb, LocalDateTime.now());
            log.info("同步工作空间成功: workspaceId={}, path={}", id, localPath);
            return getById(id);
        } catch (Exception ex) {
            updateSyncStatus(id, WorkspaceStatusEnum.ERROR.getCode(), ex.getMessage(), null, null);
            log.error("同步工作空间失败: workspaceId={}, path={}", id, localPath, ex);
            throw new BusinessException(ResultCode.WORKSPACE_SYNC_FAILED, ex.getMessage());
        }
    }

    private WorkspaceVO createGitWorkspace(Workspace workspace, WorkspaceCreateRequest request) {
        Path workspacePath = workspacePathResolver.resolveWorkspacePath(workspace.getTenantId(), workspace.getId());
        ensureWorkspacePathReady(workspacePath);
        Workspace updatePath = new Workspace();
        updatePath.setId(workspace.getId());
        updatePath.setLocalPath(workspacePath.toString());
        if (!StringUtils.hasText(workspace.getDefaultBranch())) {
            updatePath.setDefaultBranch("main");
        }
        updatePath.setWorkspaceStatus(WorkspaceStatusEnum.CLONING.getCode());
        workspaceMapper.updateById(updatePath);

        try {
            gitOperationService.cloneRepository(
                    request.getGitUrl(),
                    StringUtils.hasText(request.getDefaultBranch()) ? request.getDefaultBranch() : "main",
                    workspacePath.toString(),
                    request.getGitCredential()
            );
            long diskUsageMb = gitOperationService.calculateDiskUsageMb(workspacePath.toString());
            updateSyncStatus(workspace.getId(), WorkspaceStatusEnum.READY.getCode(), null, diskUsageMb, LocalDateTime.now());
            log.info("创建 Git 工作空间成功: workspaceId={}, path={}", workspace.getId(), workspacePath);
            return getById(workspace.getId());
        } catch (Exception ex) {
            updateSyncStatus(workspace.getId(), WorkspaceStatusEnum.ERROR.getCode(), ex.getMessage(), null, null);
            log.error("创建 Git 工作空间失败: workspaceId={}, path={}", workspace.getId(), workspacePath, ex);
            throw new BusinessException(ResultCode.WORKSPACE_CLONE_FAILED, ex.getMessage());
        }
    }

    private void ensureWorkspacePathReady(Path workspacePath) {
        try {
            Path parent = workspacePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
                if (!Files.isWritable(parent)) {
                    throw new BusinessException(ResultCode.WORKSPACE_PATH_CONFLICT, "工作空间父目录无写权限: " + parent);
                }
            }
        } catch (IOException e) {
            throw new BusinessException(ResultCode.WORKSPACE_PATH_CONFLICT, "无法创建工作空间目录: " + workspacePath);
        }
    }

    private WorkspaceVO createManualWorkspace(Workspace workspace) {
        Path workspacePath = workspacePathResolver.resolveWorkspacePath(workspace.getTenantId(), workspace.getId());
        ensureWorkspacePathReady(workspacePath);
        ensureDirectoryExists(workspacePath);

        Workspace updateEntity = new Workspace();
        updateEntity.setId(workspace.getId());
        updateEntity.setLocalPath(workspacePath.toString());
        updateEntity.setWorkspaceStatus(WorkspaceStatusEnum.READY.getCode());
        updateEntity.setDiskUsageMb(gitOperationService.calculateDiskUsageMb(workspacePath.toString()));
        updateEntity.setLastSyncAt(LocalDateTime.now());
        updateEntity.setErrorMessage(null);
        workspaceMapper.updateById(updateEntity);

        log.info("创建手动工作空间成功: workspaceId={}, path={}", workspace.getId(), workspacePath);
        return getById(workspace.getId());
    }

    private WorkspaceVO createLocalWorkspace(Workspace workspace, WorkspaceCreateRequest request) {
        Path localPath = StringUtils.hasText(request.getLocalPath())
                ? workspacePathResolver.validateWithinWorkspaceRoot(request.getLocalPath())
                : workspacePathResolver.resolveWorkspacePath(workspace.getTenantId(), workspace.getId());
        ensureDirectoryExists(localPath);

        Workspace updateEntity = new Workspace();
        updateEntity.setId(workspace.getId());
        updateEntity.setLocalPath(localPath.toString());
        updateEntity.setWorkspaceStatus(WorkspaceStatusEnum.READY.getCode());
        updateEntity.setDiskUsageMb(gitOperationService.calculateDiskUsageMb(localPath.toString()));
        updateEntity.setLastSyncAt(LocalDateTime.now());
        updateEntity.setErrorMessage(null);
        workspaceMapper.updateById(updateEntity);

        log.info("创建本地工作空间成功: workspaceId={}, path={}", workspace.getId(), localPath);
        return getById(workspace.getId());
    }

    private void refreshDiskUsage(Workspace workspace) {
        if (!StringUtils.hasText(workspace.getLocalPath())) {
            return;
        }
        try {
            Path localPath = resolveAndValidateSyncPath(workspace);
            long diskUsageMb = gitOperationService.calculateDiskUsageMb(localPath.toString());
            if (!java.util.Objects.equals(workspace.getDiskUsageMb(), diskUsageMb)) {
                Workspace patch = new Workspace();
                patch.setId(workspace.getId());
                patch.setDiskUsageMb(diskUsageMb);
                workspaceMapper.updateById(patch);
                workspace.setDiskUsageMb(diskUsageMb);
            }
        } catch (Exception ex) {
            log.warn("实时计算工作空间磁盘占用失败: workspaceId={}", workspace.getId(), ex);
        }
    }

    private void ensureDirectoryExists(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.WORKSPACE_PATH_CONFLICT, "无法创建工作空间目录: " + path);
        }
    }

    private Path resolveAndValidateSyncPath(Workspace workspace) {
        if (StringUtils.hasText(workspace.getLocalPath())) {
            return workspacePathResolver.validateWithinWorkspaceRoot(workspace.getLocalPath());
        }
        return workspacePathResolver.resolveWorkspacePath(workspace.getTenantId(), workspace.getId());
    }

    private void updateSyncStatus(String id, String status, String errorMessage, Long diskUsageMb, LocalDateTime syncAt) {
        Workspace updateEntity = new Workspace();
        updateEntity.setId(id);
        updateEntity.setWorkspaceStatus(status);
        updateEntity.setErrorMessage(errorMessage);
        if (diskUsageMb != null) {
            updateEntity.setDiskUsageMb(diskUsageMb);
        }
        if (syncAt != null) {
            updateEntity.setLastSyncAt(syncAt);
        }
        workspaceMapper.updateById(updateEntity);
    }

    private String requireTenantId() {
        String tenantId = SecurityUtil.getCurrentTenantId();
        if (!StringUtils.hasText(tenantId)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "缺少租户上下文");
        }
        return tenantId;
    }

    private String normalizeSourceType(String sourceType) {
        if (!StringUtils.hasText(sourceType)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "工作空间来源类型不能为空");
        }
        String normalized = sourceType.trim().toLowerCase(Locale.ROOT);
        boolean supported = Arrays.stream(WorkspaceSourceTypeEnum.values())
                .anyMatch(item -> item.getCode().equals(normalized));
        if (!supported) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的工作空间来源类型");
        }
        return normalized;
    }

    private void validateCreateRequestBySource(String sourceType, WorkspaceCreateRequest request) {
        if (WorkspaceSourceTypeEnum.GIT.getCode().equals(sourceType) && !StringUtils.hasText(request.getGitUrl())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "Git 导入必须提供仓库地址");
        }
    }

    @Override
    public List<WorkspaceVO> listAll() {
        String tenantId = SecurityUtil.getCurrentTenantId();
        List<Workspace> workspaces = workspaceMapper.selectList(
                new LambdaQueryWrapper<Workspace>()
                        .eq(Workspace::getTenantId, tenantId)
                        .eq(Workspace::getWorkspaceStatus, WorkspaceStatusEnum.READY.getCode())
                        .orderByDesc(Workspace::getCreatedAt));
        return workspaceConverter.toVOList(workspaces);
    }

    @Override
    public List<GitOperationService.BranchInfo> listBranches(String id) {
        Workspace workspace = entityValidator.requireExists(workspaceMapper, id, ResultCode.WORKSPACE_NOT_FOUND);
        Path worktreePath = resolveAndValidateSyncPath(workspace);
        try {
            return gitOperationService.listBranches(worktreePath.toString());
        } catch (IOException e) {
            log.error("列出分支失败: workspaceId={}", id, e);
            throw new BusinessException(ResultCode.BAD_REQUEST, "列出分支失败: " + e.getMessage());
        }
    }

    @Override
    public String getCurrentBranch(String id) {
        Workspace workspace = entityValidator.requireExists(workspaceMapper, id, ResultCode.WORKSPACE_NOT_FOUND);
        Path worktreePath = resolveAndValidateSyncPath(workspace);
        try {
            return gitOperationService.getCurrentBranch(worktreePath.toString());
        } catch (IOException e) {
            log.error("获取当前分支失败: workspaceId={}", id, e);
            throw new BusinessException(ResultCode.BAD_REQUEST, "获取当前分支失败: " + e.getMessage());
        }
    }

    @Override
    public GitOperationService.BranchInfo createBranch(String id, String branchName, String startPoint) {
        Workspace workspace = entityValidator.requireExists(workspaceMapper, id, ResultCode.WORKSPACE_NOT_FOUND);
        Path worktreePath = resolveAndValidateSyncPath(workspace);
        try {
            gitOperationService.createBranch(worktreePath.toString(), branchName, startPoint);
            // 返回新创建的分支信息
            List<GitOperationService.BranchInfo> branches = gitOperationService.listBranches(worktreePath.toString());
            return branches.stream()
                    .filter(b -> branchName.equals(b.name()))
                    .findFirst()
                    .orElse(new GitOperationService.BranchInfo(branchName, false, null));
        } catch (Exception e) {
            log.error("创建分支失败: workspaceId={}, branch={}", id, branchName, e);
            throw new BusinessException(ResultCode.BAD_REQUEST, "创建分支失败: " + e.getMessage());
        }
    }

    @Override
    public List<WorkspaceFileVO> listFiles(String id, String path) {
        Workspace workspace = entityValidator.requireExists(workspaceMapper, id, ResultCode.WORKSPACE_NOT_FOUND);
        requireBrowsable(workspace);
        Path targetPath = resolveWorkspaceFilePath(workspace, path);
        if (!Files.exists(targetPath) || !Files.isDirectory(targetPath)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "目录不存在: " + defaultIfBlank(path, "/"));
        }
        try (Stream<Path> stream = Files.list(targetPath)) {
            return stream
                    .sorted(Comparator
                            .comparing((Path item) -> !Files.isDirectory(item))
                            .thenComparing(item -> item.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .map(item -> buildWorkspaceFileVO(relativePathWithinWorkspace(workspace, item), item))
                    .toList();
        } catch (IOException e) {
            log.error("列出工作空间文件失败: workspaceId={}, path={}", id, path, e);
            throw new BusinessException(ResultCode.FAIL, "读取工作空间目录失败");
        }
    }

    @Override
    public WorkspaceFileContentVO readFile(String id, String path) {
        Workspace workspace = entityValidator.requireExists(workspaceMapper, id, ResultCode.WORKSPACE_NOT_FOUND);
        requireBrowsable(workspace);
        Path filePath = resolveWorkspaceFilePath(workspace, path);
        requireRegularFile(filePath, path);
        if (!isPreviewable(filePath)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "当前文件类型不支持在线预览");
        }
        try {
            WorkspaceFileContentVO vo = new WorkspaceFileContentVO();
            WorkspaceFileVO base = buildWorkspaceFileVO(relativePathWithinWorkspace(workspace, filePath), filePath);
            vo.setPath(base.getPath());
            vo.setName(base.getName());
            vo.setDirectory(base.getDirectory());
            vo.setSize(base.getSize());
            vo.setModifiedAt(base.getModifiedAt());
            vo.setPreviewable(base.getPreviewable());
            vo.setDownloadable(base.getDownloadable());
            vo.setMimeType(resolveMimeType(filePath));
            long fileSize = Files.size(filePath);
            vo.setTruncated(fileSize > TEXT_PREVIEW_LIMIT);
            byte[] bytes = Files.readAllBytes(filePath);
            if (bytes.length > TEXT_PREVIEW_LIMIT) {
                vo.setContent(new String(bytes, 0, (int) TEXT_PREVIEW_LIMIT, StandardCharsets.UTF_8));
            } else {
                vo.setContent(new String(bytes, StandardCharsets.UTF_8));
            }
            return vo;
        } catch (IOException e) {
            log.error("读取工作空间文件失败: workspaceId={}, path={}", id, path, e);
            throw new BusinessException(ResultCode.FAIL, "读取工作空间文件失败");
        }
    }

    @Override
    public WorkspaceFileDownload downloadFile(String id, String path) {
        Workspace workspace = entityValidator.requireExists(workspaceMapper, id, ResultCode.WORKSPACE_NOT_FOUND);
        requireDownloadable(workspace);
        Path filePath = resolveWorkspaceFilePath(workspace, path);
        requireRegularFile(filePath, path);
        try {
            return new WorkspaceFileDownload(filePath.getFileName().toString(), resolveMimeType(filePath), Files.readAllBytes(filePath));
        } catch (IOException e) {
            log.error("下载工作空间文件失败: workspaceId={}, path={}", id, path, e);
            throw new BusinessException(ResultCode.FAIL, "下载工作空间文件失败");
        }
    }

    @Override
    public List<ArtifactVO> listArtifacts(String id) {
        entityValidator.requireExists(workspaceMapper, id, ResultCode.WORKSPACE_NOT_FOUND);
        return artifactService.listByWorkspaceId(id);
    }

    private WorkspaceFileVO buildWorkspaceFileVO(Path relativePath, Path absolutePath) {
        WorkspaceFileVO vo = new WorkspaceFileVO();
        vo.setPath(normalizeRelativePath(relativePath));
        vo.setName(absolutePath.getFileName().toString());
        vo.setDirectory(Files.isDirectory(absolutePath));
        try {
            vo.setSize(Files.isDirectory(absolutePath) ? null : Files.size(absolutePath));
            FileTime lastModifiedTime = Files.getLastModifiedTime(absolutePath);
            vo.setModifiedAt(LocalDateTime.ofInstant(lastModifiedTime.toInstant(), java.time.ZoneId.systemDefault()));
        } catch (IOException e) {
            vo.setSize(null);
            vo.setModifiedAt(null);
        }
        vo.setPreviewable(!Boolean.TRUE.equals(vo.getDirectory()) && isPreviewable(absolutePath));
        vo.setDownloadable(!Boolean.TRUE.equals(vo.getDirectory()));
        return vo;
    }

    private void requireBrowsable(Workspace workspace) {
        if (!"browsable".equalsIgnoreCase(workspace.getBrowseCapability())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "当前工作空间不支持浏览文件");
        }
    }

    private void requireDownloadable(Workspace workspace) {
        if (!"downloadable".equalsIgnoreCase(workspace.getDownloadCapability())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "当前工作空间不支持下载文件");
        }
    }

    private void requireRegularFile(Path filePath, String path) {
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文件不存在: " + defaultIfBlank(path, "/"));
        }
    }

    private Path resolveWorkspaceFilePath(Workspace workspace, String relativePath) {
        Path rootPath = resolveAndValidateSyncPath(workspace);
        ensureDirectoryExists(rootPath);
        if (!StringUtils.hasText(relativePath) || "/".equals(relativePath.trim())) {
            return rootPath;
        }
        Path resolved = rootPath.resolve(relativePath).normalize();
        if (!resolved.startsWith(rootPath)) {
            throw new BusinessException(ResultCode.WORKSPACE_PATH_CONFLICT, "文件路径越界");
        }
        return resolved;
    }

    private Path relativePathWithinWorkspace(Workspace workspace, Path filePath) {
        Path rootPath = resolveAndValidateSyncPath(workspace);
        return rootPath.relativize(filePath);
    }

    private String normalizeRelativePath(Path relativePath) {
        if (relativePath == null || Objects.equals(relativePath.toString(), "")) {
            return "";
        }
        return relativePath.toString().replace('\\', '/');
    }

    private boolean isPreviewable(Path filePath) {
        String lower = filePath.getFileName().toString().toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt")
                || lower.endsWith(".json") || lower.endsWith(".yml") || lower.endsWith(".yaml")
                || lower.endsWith(".html") || lower.endsWith(".csv");
    }

    private String resolveMimeType(Path filePath) {
        try {
            String mimeType = Files.probeContentType(filePath);
            if (StringUtils.hasText(mimeType)) {
                return mimeType;
            }
        } catch (IOException ignored) {
            // ignore
        }
        String lower = filePath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return "text/markdown";
        }
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
            return "application/yaml";
        }
        if (lower.endsWith(".html")) {
            return "text/html";
        }
        if (lower.endsWith(".csv")) {
            return "text/csv";
        }
        return "text/plain";
    }

    private void applyWorkspaceCapabilities(Workspace workspace, String sourceType) {
        if (workspace == null) {
            return;
        }
        if (WorkspaceSourceTypeEnum.LOCAL.getCode().equals(sourceType)) {
            workspace.setAccessMode("server_path");
        } else {
            workspace.setAccessMode("managed");
        }
        workspace.setWriteCapability("writable");
        workspace.setBrowseCapability("browsable");
        workspace.setDownloadCapability("downloadable");
    }

    private String defaultIfBlank(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }
}
