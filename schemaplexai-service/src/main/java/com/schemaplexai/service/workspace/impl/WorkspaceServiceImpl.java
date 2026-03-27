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
import com.schemaplexai.model.vo.workspace.WorkspaceVO;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

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
        if (WorkspaceSourceTypeEnum.MANUAL.getCode().equals(sourceType)) {
            entity.setLocalPath(null);
        }

        workspaceMapper.insert(entity);

        if (WorkspaceSourceTypeEnum.GIT.getCode().equals(sourceType)) {
            return createGitWorkspace(entity, request);
        }
        if (WorkspaceSourceTypeEnum.LOCAL.getCode().equals(sourceType)) {
            return createLocalWorkspace(entity, request);
        }

        log.info("创建手动工作空间成功: workspaceId={}", entity.getId());
        return getById(entity.getId());
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
        return new PageResult<>(workspaceConverter.toVOList(result.getRecords()),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public WorkspaceVO getById(String id) {
        Workspace workspace = entityValidator.requireExists(workspaceMapper, id, ResultCode.WORKSPACE_NOT_FOUND);
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
            return workspaceConverter.toVO(workspace);
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

    private WorkspaceVO createLocalWorkspace(Workspace workspace, WorkspaceCreateRequest request) {
        Path localPath = StringUtils.hasText(request.getLocalPath())
                ? workspacePathResolver.validateWithinWorkspaceRoot(request.getLocalPath())
                : workspacePathResolver.resolveWorkspacePath(workspace.getTenantId(), workspace.getId());

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
}
