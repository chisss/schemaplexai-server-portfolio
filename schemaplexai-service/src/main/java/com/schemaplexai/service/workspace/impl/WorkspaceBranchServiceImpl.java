package com.schemaplexai.service.workspace.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.BranchRuleMapper;
import com.schemaplexai.dao.mapper.WorkspaceBranchMapper;
import com.schemaplexai.dao.mapper.WorkspaceMapper;
import com.schemaplexai.model.dto.workspace.BranchRuleSaveRequest;
import com.schemaplexai.model.dto.workspace.WorkspaceBranchQueryRequest;
import com.schemaplexai.model.entity.BranchRule;
import com.schemaplexai.model.entity.Workspace;
import com.schemaplexai.model.entity.WorkspaceBranch;
import com.schemaplexai.model.vo.workspace.BranchRuleVO;
import com.schemaplexai.model.vo.workspace.WorkspaceBranchDiffVO;
import com.schemaplexai.model.vo.workspace.WorkspaceBranchVO;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.integration.git.GitOperationService;
import com.schemaplexai.service.workspace.WorkspaceBranchService;
import com.schemaplexai.service.workspace.WorkspacePathResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 工作空间分支管理服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceBranchServiceImpl implements WorkspaceBranchService {

    private static final Pattern REQUIREMENT_PATTERN = Pattern.compile("([A-Za-z]+-\\d+)");

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceBranchMapper workspaceBranchMapper;
    private final BranchRuleMapper branchRuleMapper;
    private final EntityValidator entityValidator;
    private final WorkspacePathResolver workspacePathResolver;
    private final GitOperationService gitOperationService;

    @Override
    public PageResult<WorkspaceBranchVO> pageBranches(WorkspaceBranchQueryRequest request) {
        syncRequestedWorkspaces(request);

        Page<WorkspaceBranch> page = new Page<>(request.getPage(), request.getSize());
        LambdaQueryWrapper<WorkspaceBranch> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(request.getWorkspaceId())) {
            wrapper.eq(WorkspaceBranch::getWorkspaceId, request.getWorkspaceId());
        }
        if (StringUtils.hasText(request.getRequirementNo())) {
            wrapper.eq(WorkspaceBranch::getRequirementNo, request.getRequirementNo().trim().toUpperCase());
        }
        if (StringUtils.hasText(request.getBranchName())) {
            wrapper.like(WorkspaceBranch::getBranchName, request.getBranchName().trim());
        }
        wrapper.orderByDesc(WorkspaceBranch::getIsCurrent)
                .orderByDesc(WorkspaceBranch::getLastSyncedAt)
                .orderByAsc(WorkspaceBranch::getBranchName);

        var result = workspaceBranchMapper.selectPage(page, wrapper);
        Map<String, String> workspaceNameMap = loadWorkspaceNameMap(result.getRecords().stream()
                .map(WorkspaceBranch::getWorkspaceId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet()));
        List<WorkspaceBranchVO> records = result.getRecords().stream()
                .map(branch -> toWorkspaceBranchVO(branch, workspaceNameMap.get(branch.getWorkspaceId())))
                .toList();
        return new PageResult<>(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public WorkspaceBranchDiffVO getBranchDiff(String workspaceId, String sourceBranch, String targetBranch) {
        if (!StringUtils.hasText(sourceBranch)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "源分支不能为空");
        }
        Workspace workspace = entityValidator.requireExists(workspaceMapper, workspaceId, ResultCode.WORKSPACE_NOT_FOUND);
        Path workspacePath = resolveWorkspacePath(workspace);
        Path repositoryPath = resolveBranchRepositoryPath(workspace);
        Map<String, Path> branchWorktreePathMap = buildBranchWorktreePathMap(workspacePath);
        String resolvedTarget = StringUtils.hasText(targetBranch) ? targetBranch : resolveTargetBranch(workspace);
        try {
            WorkspaceBranchDiffVO diff = gitOperationService.compareBranches(
                    repositoryPath.toString(),
                    sourceBranch,
                    resolvedTarget,
                    toPathString(branchWorktreePathMap.get(sourceBranch)),
                    toPathString(branchWorktreePathMap.get(resolvedTarget))
            );
            diff.setWorkspaceId(workspaceId);
            diff.setWorkspaceName(workspace.getName());
            return diff;
        } catch (IOException e) {
            log.error("获取分支差异失败: workspaceId={}, sourceBranch={}, targetBranch={}",
                    workspaceId, sourceBranch, resolvedTarget, e);
            throw new BusinessException(ResultCode.BAD_REQUEST, "获取分支差异失败: " + e.getMessage());
        }
    }

    @Override
    public List<BranchRuleVO> listBranchRules(String workspaceId) {
        LambdaQueryWrapper<BranchRule> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(workspaceId)) {
            wrapper.eq(BranchRule::getWorkspaceId, workspaceId);
        }
        wrapper.orderByDesc(BranchRule::getUpdatedAt);
        List<BranchRule> rules = branchRuleMapper.selectList(wrapper);
        Map<String, String> workspaceNameMap = loadWorkspaceNameMap(rules.stream()
                .map(BranchRule::getWorkspaceId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet()));
        return rules.stream()
                .map(rule -> toBranchRuleVO(rule, workspaceNameMap.get(rule.getWorkspaceId())))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BranchRuleVO createBranchRule(BranchRuleSaveRequest request) {
        entityValidator.requireExists(workspaceMapper, request.getWorkspaceId(), ResultCode.WORKSPACE_NOT_FOUND);
        BranchRule exists = branchRuleMapper.selectOne(new LambdaQueryWrapper<BranchRule>()
                .eq(BranchRule::getWorkspaceId, request.getWorkspaceId())
                .last("LIMIT 1"));
        if (exists != null) {
            throw new BusinessException(ResultCode.FAIL, "当前工作空间已存在分支规则");
        }
        BranchRule entity = new BranchRule();
        entity.setTenantId(SecurityUtil.getCurrentTenantId());
        entity.setWorkspaceId(request.getWorkspaceId());
        entity.setBranchPattern(request.getBranchPattern());
        entity.setBranchNameTemplate(request.getBranchNameTemplate());
        entity.setAutoCreateForSpec(Boolean.TRUE.equals(request.getAutoCreateForSpec()));
        entity.setProtectionRules(request.getProtectionRules());
        branchRuleMapper.insert(entity);
        return getBranchRuleVO(entity.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BranchRuleVO updateBranchRule(String id, BranchRuleSaveRequest request) {
        BranchRule exists = entityValidator.requireExists(branchRuleMapper, id, ResultCode.CONFIG_NOT_FOUND);
        entityValidator.requireExists(workspaceMapper, request.getWorkspaceId(), ResultCode.WORKSPACE_NOT_FOUND);
        BranchRule patch = new BranchRule();
        patch.setId(id);
        patch.setWorkspaceId(request.getWorkspaceId());
        patch.setBranchPattern(request.getBranchPattern());
        patch.setBranchNameTemplate(request.getBranchNameTemplate());
        patch.setAutoCreateForSpec(Boolean.TRUE.equals(request.getAutoCreateForSpec()));
        patch.setProtectionRules(request.getProtectionRules());
        patch.setIntegrationProjectId(exists.getIntegrationProjectId());
        branchRuleMapper.updateById(patch);
        return getBranchRuleVO(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteBranchRule(String id) {
        entityValidator.requireExists(branchRuleMapper, id, ResultCode.CONFIG_NOT_FOUND);
        branchRuleMapper.deleteById(id);
    }

    private void syncRequestedWorkspaces(WorkspaceBranchQueryRequest request) {
        if (StringUtils.hasText(request.getWorkspaceId())) {
            Workspace workspace = entityValidator.requireExists(workspaceMapper, request.getWorkspaceId(), ResultCode.WORKSPACE_NOT_FOUND);
            syncBranches(workspace);
            return;
        }
        List<Workspace> workspaces = workspaceMapper.selectList(new LambdaQueryWrapper<Workspace>()
                .eq(Workspace::getTenantId, SecurityUtil.getCurrentTenantId())
                .orderByDesc(Workspace::getCreatedAt));
        workspaces.forEach(this::syncBranches);
    }

    private void syncBranches(Workspace workspace) {
        if (workspace == null || !"git".equalsIgnoreCase(workspace.getSourceType())) {
            return;
        }
        Path workspacePath;
        Path repositoryPath;
        try {
            workspacePath = resolveWorkspacePath(workspace);
            repositoryPath = resolveBranchRepositoryPath(workspace);
        } catch (Exception ex) {
            log.warn("跳过同步分支，工作空间路径不可用: workspaceId={}", workspace.getId(), ex);
            return;
        }
        String targetBranch = resolveTargetBranch(workspace);
        try {
            List<GitOperationService.BranchInfo> branches = collectManagedBranches(repositoryPath, workspacePath);
            Map<String, Path> branchWorktreePathMap = buildBranchWorktreePathMap(workspacePath);
            LocalDateTime now = LocalDateTime.now();
            for (GitOperationService.BranchInfo branchInfo : branches) {
                WorkspaceBranch entity = workspaceBranchMapper.selectOne(new LambdaQueryWrapper<WorkspaceBranch>()
                        .eq(WorkspaceBranch::getWorkspaceId, workspace.getId())
                        .eq(WorkspaceBranch::getBranchName, branchInfo.name())
                        .last("LIMIT 1"));
                if (entity == null) {
                    entity = new WorkspaceBranch();
                    entity.setTenantId(workspace.getTenantId());
                    entity.setWorkspaceId(workspace.getId());
                    entity.setBranchName(branchInfo.name());
                    entity.setCreatedBy(SecurityUtil.getCurrentUserId());
                    entity.setDeleted(0);
                }
                entity.setTargetBranch(targetBranch);
                entity.setSourceBranch(branchInfo.name());
                entity.setHeadCommit(branchInfo.commitId());
                entity.setRequirementNo(extractRequirementNo(branchInfo.name()));
                entity.setBranchStatus("active");
                entity.setIsCurrent(branchInfo.isCurrent());
                entity.setLastSyncedAt(now);
                if (StringUtils.hasText(targetBranch) && !Objects.equals(targetBranch, branchInfo.name())) {
                    WorkspaceBranchDiffVO diff = gitOperationService.compareBranches(
                            repositoryPath.toString(),
                            branchInfo.name(),
                            targetBranch,
                            toPathString(branchWorktreePathMap.get(branchInfo.name())),
                            toPathString(branchWorktreePathMap.get(targetBranch))
                    );
                    entity.setAheadCount(diff.getAheadCount());
                    entity.setBehindCount(diff.getBehindCount());
                    entity.setDiffSummary(Map.of(
                            "changedFileCount", diff.getChangedFileCount(),
                            "additions", diff.getAdditions(),
                            "deletions", diff.getDeletions()
                    ));
                } else {
                    entity.setAheadCount(0);
                    entity.setBehindCount(0);
                    entity.setDiffSummary(Map.of("changedFileCount", 0, "additions", 0, "deletions", 0));
                }
                entity.setUpdatedBy(SecurityUtil.getCurrentUserId());
                if (StringUtils.hasText(entity.getId())) {
                    workspaceBranchMapper.updateById(entity);
                } else {
                    workspaceBranchMapper.insert(entity);
                }
            }
        } catch (Exception ex) {
            log.warn("同步工作空间分支失败: workspaceId={}", workspace.getId(), ex);
        }
    }

    private Map<String, Path> buildBranchWorktreePathMap(Path workspacePath) {
        Map<String, Path> branchPathMap = new LinkedHashMap<>();
        mergeBranchPath(branchPathMap, workspacePath);

        Path worktreesPath = workspacePath.resolve(".worktrees");
        if (!Files.isDirectory(worktreesPath)) {
            return branchPathMap;
        }

        try (var stream = Files.list(worktreesPath)) {
            stream.filter(Files::isDirectory)
                    .forEach(path -> mergeBranchPath(branchPathMap, path));
        } catch (IOException ex) {
            log.warn("扫描工作树目录失败: workspacePath={}", workspacePath, ex);
        }
        return branchPathMap;
    }

    private void mergeBranchPath(Map<String, Path> branchPathMap, Path candidatePath) {
        try {
            String branchName = gitOperationService.getCurrentBranch(candidatePath.toString());
            if (!StringUtils.hasText(branchName)) {
                return;
            }
            Path existingPath = branchPathMap.get(branchName);
            if (existingPath == null || scoreBranchPath(candidatePath) > scoreBranchPath(existingPath)) {
                branchPathMap.put(branchName, candidatePath);
            }
        } catch (Exception ex) {
            log.debug("解析分支工作树路径失败: path={}", candidatePath, ex);
        }
    }

    private long scoreBranchPath(Path path) {
        if (!Files.isDirectory(path)) {
            return 0L;
        }
        try (var stream = Files.list(path)) {
            return stream
                    .filter(candidate -> {
                        String name = candidate.getFileName().toString();
                        return !".git".equals(name) && !".mirror.git".equals(name) && !".worktrees".equals(name);
                    })
                    .count();
        } catch (IOException ex) {
            return 0L;
        }
    }

    private String toPathString(Path path) {
        return path == null ? null : path.toString();
    }

    private List<GitOperationService.BranchInfo> collectManagedBranches(Path repositoryPath, Path workspacePath) throws IOException {
        Map<String, GitOperationService.BranchInfo> branchMap = new LinkedHashMap<>();
        gitOperationService.listBranches(repositoryPath.toString())
                .forEach(branch -> branchMap.put(branch.name(), branch));

        Path worktreesPath = workspacePath.resolve(".worktrees");
        if (!Files.isDirectory(worktreesPath)) {
            return new ArrayList<>(branchMap.values());
        }

        try (var stream = Files.list(worktreesPath)) {
            stream.filter(Files::isDirectory)
                    .forEach(path -> mergeWorktreeBranch(branchMap, path));
        }
        return new ArrayList<>(branchMap.values());
    }

    private void mergeWorktreeBranch(Map<String, GitOperationService.BranchInfo> branchMap, Path worktreePath) {
        try {
            String branchName = gitOperationService.getCurrentBranch(worktreePath.toString());
            if (!StringUtils.hasText(branchName)) {
                return;
            }
            String commitId = gitOperationService.getHeadCommitId(worktreePath.toString());
            branchMap.merge(branchName,
                    new GitOperationService.BranchInfo(branchName, true, commitId),
                    (existing, candidate) -> new GitOperationService.BranchInfo(
                            existing.name(),
                            existing.isCurrent() || candidate.isCurrent(),
                            StringUtils.hasText(candidate.commitId()) ? candidate.commitId() : existing.commitId()
                    ));
        } catch (Exception ex) {
            log.debug("读取工作树分支失败: path={}", worktreePath, ex);
        }
    }

    private Path resolveWorkspacePath(Workspace workspace) {
        if (StringUtils.hasText(workspace.getLocalPath())) {
            return workspacePathResolver.validateWithinWorkspaceRoot(workspace.getLocalPath());
        }
        return workspacePathResolver.resolveWorkspacePath(workspace.getTenantId(), workspace.getId());
    }

    private Path resolveBranchRepositoryPath(Workspace workspace) {
        Path workspacePath = resolveWorkspacePath(workspace);
        Path mirrorPath = workspacePath.resolve(".mirror.git");
        if (Files.isDirectory(mirrorPath)) {
            return mirrorPath;
        }
        return workspacePath;
    }

    private String resolveTargetBranch(Workspace workspace) {
        BranchRule rule = branchRuleMapper.selectOne(new LambdaQueryWrapper<BranchRule>()
                .eq(BranchRule::getWorkspaceId, workspace.getId())
                .last("LIMIT 1"));
        if (rule != null && StringUtils.hasText(rule.getBranchPattern())) {
            return rule.getBranchPattern();
        }
        return StringUtils.hasText(workspace.getDefaultBranch()) ? workspace.getDefaultBranch() : "master";
    }

    private String extractRequirementNo(String branchName) {
        if (!StringUtils.hasText(branchName)) {
            return null;
        }
        Matcher matcher = REQUIREMENT_PATTERN.matcher(branchName);
        return matcher.find() ? matcher.group(1).toUpperCase() : null;
    }

    private Map<String, String> loadWorkspaceNameMap(Collection<String> workspaceIds) {
        if (workspaceIds == null || workspaceIds.isEmpty()) {
            return Map.of();
        }
        return workspaceMapper.selectBatchIds(new ArrayList<>(workspaceIds)).stream()
                .collect(Collectors.toMap(Workspace::getId, Workspace::getName, (left, right) -> left, LinkedHashMap::new));
    }

    private WorkspaceBranchVO toWorkspaceBranchVO(WorkspaceBranch entity, String workspaceName) {
        WorkspaceBranchVO vo = new WorkspaceBranchVO();
        vo.setId(entity.getId());
        vo.setWorkspaceId(entity.getWorkspaceId());
        vo.setWorkspaceName(workspaceName);
        vo.setBranchName(entity.getBranchName());
        vo.setRequirementNo(entity.getRequirementNo());
        vo.setSourceBranch(entity.getSourceBranch());
        vo.setTargetBranch(entity.getTargetBranch());
        vo.setHeadCommit(entity.getHeadCommit());
        vo.setAheadCount(entity.getAheadCount());
        vo.setBehindCount(entity.getBehindCount());
        vo.setIsCurrent(entity.getIsCurrent());
        vo.setBranchStatus(entity.getBranchStatus());
        vo.setLastSyncedAt(entity.getLastSyncedAt());
        vo.setDiffSummary(entity.getDiffSummary());
        return vo;
    }

    private BranchRuleVO getBranchRuleVO(String id) {
        BranchRule entity = entityValidator.requireExists(branchRuleMapper, id, ResultCode.CONFIG_NOT_FOUND);
        String workspaceName = null;
        if (StringUtils.hasText(entity.getWorkspaceId())) {
            Workspace workspace = workspaceMapper.selectById(entity.getWorkspaceId());
            workspaceName = workspace == null ? null : workspace.getName();
        }
        return toBranchRuleVO(entity, workspaceName);
    }

    private BranchRuleVO toBranchRuleVO(BranchRule entity, String workspaceName) {
        BranchRuleVO vo = new BranchRuleVO();
        vo.setId(entity.getId());
        vo.setWorkspaceId(entity.getWorkspaceId());
        vo.setWorkspaceName(workspaceName);
        vo.setBranchPattern(entity.getBranchPattern());
        vo.setBranchNameTemplate(entity.getBranchNameTemplate());
        vo.setAutoCreateForSpec(entity.getAutoCreateForSpec());
        vo.setProtectionRules(entity.getProtectionRules());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }
}
