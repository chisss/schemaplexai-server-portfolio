package com.schemaplexai.service.evaluation.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.common.util.SystemTenantContext;
import com.schemaplexai.dao.mapper.EvalDatasetItemMapper;
import com.schemaplexai.dao.mapper.EvalDatasetMapper;
import com.schemaplexai.dao.mapper.EvalResultMapper;
import com.schemaplexai.dao.mapper.EvalTaskMapper;
import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.model.converter.EvaluationConverter;
import com.schemaplexai.model.dto.evaluation.EvalTaskCreateRequest;
import com.schemaplexai.model.dto.evaluation.EvalTaskQueryRequest;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AiModel;
import com.schemaplexai.model.entity.EvalDataset;
import com.schemaplexai.model.entity.EvalDatasetItem;
import com.schemaplexai.model.entity.EvalResult;
import com.schemaplexai.model.entity.EvalTask;
import com.schemaplexai.model.vo.evaluation.EvalTaskItemModelResultVO;
import com.schemaplexai.model.vo.evaluation.EvalTaskModelSummaryVO;
import com.schemaplexai.model.vo.evaluation.EvalTaskResultItemVO;
import com.schemaplexai.model.vo.evaluation.EvalTaskSummaryVO;
import com.schemaplexai.model.vo.evaluation.EvalTaskVerdictVO;
import com.schemaplexai.model.vo.evaluation.EvalTaskVO;
import com.schemaplexai.service.ai.AIModelRouter;
import com.schemaplexai.service.ai.AiModelConfig;
import com.schemaplexai.service.evaluation.EvalMetadataConfig;
import com.schemaplexai.service.evaluation.EvalTaskService;
import com.schemaplexai.service.quality.strategy.ModelBasedQualityEvaluator;
import com.schemaplexai.service.quality.strategy.QualityEvaluationStrategy;
import com.schemaplexai.service.evaluation.trajectory.EvalAgentExecutionBridge;
import com.schemaplexai.service.evaluation.trajectory.TrajectoryCollector;
import com.schemaplexai.service.evaluation.trajectory.TrajectoryData;
import com.schemaplexai.service.evaluation.trajectory.TrajectoryJudge;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 评估任务服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalTaskServiceImpl implements EvalTaskService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_DONE = "DONE";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_PARTIAL = "PARTIAL";

    /** 评估类型：单轮问答 */
    private static final String TASK_TYPE_QA = "QA";
    /** 评估类型：Agent 执行轨迹 */
    private static final String TASK_TYPE_TRAJECTORY = "TRAJECTORY";

    /** QA 模式评估内部并发度（LLM 调用轻量） */
    private static final int QA_CONCURRENCY = 4;
    /** TRAJECTORY 模式评估内部并发度（Agent 执行重、含工具链路） */
    private static final int TRAJECTORY_CONCURRENCY = 2;

    private static final String EVAL_SYSTEM_PROMPT = """
            你是 SchemaPlexAI 的模型评估执行器。
            请根据用户输入直接作答，不要解释评估过程，也不要输出多余前缀。
            如果输入中包含结构化要求，请严格遵守。
            """;

    /** QA 模式 LLM Judge 评审系统提示词（复用质量中心结构化评审 schema） */
    private static final String QA_JUDGE_SYSTEM_PROMPT = """
            你是 SchemaPlexAI 的模型回答评估器。
            请基于用户问题与参考基线，对被评模型的回答进行独立评审。

            评审要求：
            1. 只输出高置信度问题，不为凑数输出含糊意见
            2. 问题描述必须指向回答中的具体内容
            3. 若 hasIssue=true 则 findings 至少 1 条，summary 中提到的关键问题必须在 findings 中逐条体现
            4. 只有在确认无高置信度问题时，才允许 hasIssue=false、findings=[]、score=90-100

            请严格按照以下 JSON 格式返回，不要添加任何额外说明：
            {
              "hasIssue": true/false,
              "score": 0-100,
              "summary": "一句话总结",
              "findings": [
                {
                  "type": "correctness|completeness|format|factuality",
                  "severity": "critical|warning|info",
                  "confidence": 0-100,
                  "title": "问题标题",
                  "description": "问题描述",
                  "location": "回答中的位置",
                  "suggestion": "改进建议"
                }
              ]
            }
            """;

    private final EvalTaskMapper evalTaskMapper;
    private final EvalDatasetMapper evalDatasetMapper;
    private final EvalDatasetItemMapper evalDatasetItemMapper;
    private final EvalResultMapper evalResultMapper;
    private final AiModelMapper aiModelMapper;
    private final AgentMapper agentMapper;
    private final EvaluationConverter evaluationConverter;
    private final EvalMetricCalculator evalMetricCalculator;
    private final AIModelRouter aiModelRouter;
    private final EvalAgentExecutionBridge evalAgentExecutionBridge;
    private final TrajectoryCollector trajectoryCollector;
    private final TrajectoryJudge trajectoryJudge;
    private final ModelBasedQualityEvaluator modelBasedQualityEvaluator;

    @Qualifier("agentExecutorPool")
    private final Executor agentExecutorPool;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EvalTaskVO create(EvalTaskCreateRequest request) {
        EvalDataset dataset = requireDataset(request.getDatasetId());
        List<EvalDatasetItem> items = loadDatasetItems(dataset.getId());
        if (CollectionUtils.isEmpty(items)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "评估数据集不能为空");
        }
        String taskType = StringUtils.hasText(request.getTaskType())
                ? request.getTaskType().toUpperCase(java.util.Locale.ROOT) : TASK_TYPE_QA;
        int targetCount = resolveTargetsAndValidate(taskType, request);

        EvalTask task = new EvalTask();
        task.setTenantId(SecurityUtil.getCurrentTenantId());
        task.setName(StringUtils.hasText(request.getName()) ? request.getName() : "评估任务-" + LocalDateTime.now());
        task.setDatasetId(dataset.getId());
        task.setTaskType(taskType);
        task.setModelIds(TASK_TYPE_QA.equals(taskType) ? request.getModelIds() : null);
        task.setAgentIds(TASK_TYPE_TRAJECTORY.equals(taskType) ? request.getAgentIds() : null);
        task.setJudgeModelIds(request.getJudgeModelIds());
        // 通过分数线在创建时从数据集快照，历史判定不随数据集修改漂移
        task.setPassScoreThreshold(dataset.getPassScoreThreshold());
        task.setStatus(STATUS_PENDING);
        task.setTotalItems(items.size() * targetCount);
        task.setCompletedItems(0);
        evalTaskMapper.insert(task);

        dataset.setLastUsedAt(LocalDateTime.now());
        evalDatasetMapper.updateById(dataset);

        submitAfterCommit(() -> executeTask(task.getId()));
        return getById(task.getId());
    }

    /**
     * 校验被测目标列表并按类型返回目标数量：
     * QA 校验模型存在；TRAJECTORY 校验 Agent 存在 + Judge 模型存在
     */
    private int resolveTargetsAndValidate(String taskType, EvalTaskCreateRequest request) {
        if (TASK_TYPE_TRAJECTORY.equals(taskType)) {
            requireModels(request.getJudgeModelIds());
            List<Agent> agents = requireAgents(request.getAgentIds());
            return agents.size();
        }
        List<AiModel> models = requireModels(request.getModelIds());
        if (!CollectionUtils.isEmpty(request.getJudgeModelIds())) {
            requireModels(request.getJudgeModelIds());
        }
        return models.size();
    }

    @Override
    public PageResult<EvalTaskSummaryVO> page(EvalTaskQueryRequest request) {
        Page<EvalTask> queryPage = new Page<>(request.getPage(), request.getSize());
        LambdaQueryWrapper<EvalTask> wrapper = new LambdaQueryWrapper<>();
        String tenantId = SecurityUtil.getCurrentTenantId();
        if (StringUtils.hasText(tenantId)) {
            wrapper.eq(EvalTask::getTenantId, tenantId);
        }
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq(EvalTask::getStatus, request.getStatus());
        }
        if (StringUtils.hasText(request.getDatasetId())) {
            wrapper.eq(EvalTask::getDatasetId, request.getDatasetId());
        }
        wrapper.orderByDesc(EvalTask::getCreatedAt);
        Page<EvalTask> result = evalTaskMapper.selectPage(queryPage, wrapper);
        // 列表瘦身：只构建概要（不加载全量 results/items 明细），详情走 getById
        List<EvalTaskSummaryVO> records = result.getRecords().stream().map(this::buildSummaryVO).toList();
        return new PageResult<>(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    private EvalTaskSummaryVO buildSummaryVO(EvalTask task) {
        EvalTaskSummaryVO vo = new EvalTaskSummaryVO();
        vo.setId(task.getId());
        vo.setName(task.getName());
        vo.setDatasetId(task.getDatasetId());
        EvalDataset dataset = evalDatasetMapper.selectById(task.getDatasetId());
        vo.setDatasetName(dataset == null ? task.getDatasetId() : dataset.getName());
        vo.setTaskType(task.getTaskType());
        vo.setStatus(task.getStatus());
        vo.setTotalItems(task.getTotalItems());
        vo.setCompletedItems(task.getCompletedItems());
        vo.setProgress(task.getTotalItems() == null || task.getTotalItems() == 0
                ? 0D
                : ((task.getCompletedItems() == null ? 0 : task.getCompletedItems()) * 100.0 / task.getTotalItems()));
        vo.setErrorMessage(task.getErrorMessage());
        vo.setStartedAt(task.getStartedAt());
        vo.setFinishedAt(task.getFinishedAt());
        vo.setCreatedAt(task.getCreatedAt());
        vo.setTargetNames(resolveTargetNames(task));
        if (!CollectionUtils.isEmpty(task.getJudgeModelIds())) {
            vo.setJudgeModelNames(requireModels(task.getJudgeModelIds()).stream()
                    .map(AiModel::getName).toList());
        }
        return vo;
    }

    private List<String> resolveTargetNames(EvalTask task) {
        if (TASK_TYPE_TRAJECTORY.equalsIgnoreCase(task.getTaskType())) {
            return requireAgents(task.getAgentIds()).stream().map(Agent::getName).toList();
        }
        return requireModels(task.getModelIds()).stream().map(AiModel::getName).toList();
    }

    @Override
    public EvalTaskVO getById(String id) {
        return buildTaskVO(requireTask(id));
    }

    @Override
    public EvalTaskVO run(String id) {
        EvalTask task = requireTask(id);
        if (STATUS_RUNNING.equalsIgnoreCase(task.getStatus())) {
            return buildTaskVO(task);
        }
        task.setStatus(STATUS_PENDING);
        task.setErrorMessage(null);
        task.setCompletedItems(0);
        task.setStartedAt(null);
        task.setFinishedAt(null);
        evalTaskMapper.updateById(task);
        evalResultMapper.delete(new LambdaQueryWrapper<EvalResult>().eq(EvalResult::getTaskId, id));
        submitAfterCommit(() -> executeTask(id));
        return getById(id);
    }

    private void submitAfterCommit(Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            agentExecutorPool.execute(task);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                agentExecutorPool.execute(task);
            }
        });
    }

    /**
     * 服务启动后恢复中断的评估任务（PENDING/RUNNING → 幂等续跑，已完成组合自动跳过）
     * <p>
     * 启动期无用户租户上下文，查询走 {@link SystemTenantContext#callAsSystem} 豁免租户拦截；
     * 每条任务执行前显式设置任务自身租户（executeTask 内查询恢复租户过滤语义）。
     * 整个方法兜底 catch——listener 异常会中断 SpringApplication.run 导致应用启动失败。
     * </p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedTasks() {
        try {
            List<EvalTask> tasks = SystemTenantContext.callAsSystem(() ->
                    evalTaskMapper.selectList(new LambdaQueryWrapper<EvalTask>()
                            .in(EvalTask::getStatus, List.of(STATUS_PENDING, STATUS_RUNNING))));
            if (tasks.isEmpty()) {
                return;
            }
            log.info("恢复中断的评估任务: count={}", tasks.size());
            for (EvalTask task : tasks) {
                String tenantId = task.getTenantId();
                log.info("恢复评估任务: taskId={}, tenantId={}", task.getId(), tenantId);
                agentExecutorPool.execute(() -> {
                    String previousTenantId = SecurityUtil.getCurrentTenantId();
                    try {
                        if (StringUtils.hasText(tenantId)) {
                            SecurityUtil.setCurrentTenantId(tenantId);
                        }
                        executeTask(task.getId());
                    } catch (Exception e) {
                        log.error("恢复评估任务失败: taskId={}", task.getId(), e);
                    } finally {
                        if (StringUtils.hasText(previousTenantId)) {
                            SecurityUtil.setCurrentTenantId(previousTenantId);
                        } else {
                            SecurityUtil.clear();
                        }
                    }
                });
            }
        } catch (Exception e) {
            // 恢复失败不得影响应用启动
            log.warn("扫描待恢复评估任务失败（不影响启动）: {}", e.getMessage());
        }
    }

    private void executeTask(String taskId) {
        EvalTask task = requireTask(taskId);
        EvalDataset dataset = requireDataset(task.getDatasetId());
        List<EvalDatasetItem> items = loadDatasetItems(dataset.getId());
        task.setStatus(STATUS_RUNNING);
        task.setStartedAt(LocalDateTime.now());
        task.setFinishedAt(null);
        task.setErrorMessage(null);
        evalTaskMapper.updateById(task);

        try {
            // 幂等续跑：跳过已有结果的组合（进程重启/中断恢复后只补缺失组合）
            List<WorkItem> workItems = buildPendingWorkItems(task, items);
            if (workItems.isEmpty()) {
                log.info("评估任务无待执行组合（全部已完成，续跑场景）: taskId={}", taskId);
                task.setStatus(STATUS_DONE);
                task.setFinishedAt(LocalDateTime.now());
                evalTaskMapper.updateById(task);
                return;
            }
            int concurrency = TASK_TYPE_TRAJECTORY.equalsIgnoreCase(task.getTaskType())
                    ? TRAJECTORY_CONCURRENCY : QA_CONCURRENCY;
            executeWorkItemsInBatches(task, workItems, concurrency);

            // 终态：全成功 DONE、全失败 FAILED、混合 PARTIAL
            List<EvalResult> allResults = evalResultMapper.selectList(new LambdaQueryWrapper<EvalResult>()
                    .eq(EvalResult::getTaskId, task.getId()));
            long successCount = allResults.stream()
                    .filter(result -> "SUCCESS".equalsIgnoreCase(result.getStatus())).count();
            task.setStatus(resolveFinalStatus(successCount, allResults.size() - successCount));
            task.setFinishedAt(LocalDateTime.now());
            task.setCompletedItems(allResults.size());
            evalTaskMapper.updateById(task);
        } catch (Exception e) {
            log.error("执行评估任务失败: taskId={}", taskId, e);
            task.setStatus(STATUS_FAILED);
            task.setErrorMessage(e.getMessage());
            task.setFinishedAt(LocalDateTime.now());
            evalTaskMapper.updateById(task);
        }
    }

    /**
     * 分批并发执行组合：批内并发、批间串行，进度按批平滑推进；
     * completedItems 用原子 SQL 递增，避免并行 updateById 互相覆盖
     */
    private void executeWorkItemsInBatches(EvalTask task, List<WorkItem> workItems, int concurrency) {
        for (int i = 0; i < workItems.size(); i += concurrency) {
            List<WorkItem> batch = workItems.subList(i, Math.min(i + concurrency, workItems.size()));
            List<CompletableFuture<Void>> futures = batch.stream()
                    .map(work -> CompletableFuture.runAsync(() -> {
                        EvalResult result = executeWorkItem(task, work);
                        evalResultMapper.insert(result);
                        evalTaskMapper.incrementCompletedItems(task.getId());
                    }, agentExecutorPool))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
    }

    /**
     * 构建待执行组合：数据集条目 × 被测目标（模型/Agent），跳过已有结果的组合
     */
    private List<WorkItem> buildPendingWorkItems(EvalTask task, List<EvalDatasetItem> items) {
        Set<String> doneKeys = evalResultMapper.selectList(new LambdaQueryWrapper<EvalResult>()
                        .eq(EvalResult::getTaskId, task.getId()))
                .stream()
                .map(result -> result.getItemId() + "::" + result.getModelId())
                .collect(Collectors.toSet());
        List<WorkItem> workItems = new ArrayList<>();
        if (TASK_TYPE_TRAJECTORY.equalsIgnoreCase(task.getTaskType())) {
            for (Agent agent : requireAgents(task.getAgentIds())) {
                for (EvalDatasetItem item : items) {
                    addIfPending(workItems, doneKeys, item, null, agent);
                }
            }
        } else {
            for (AiModel model : requireModels(task.getModelIds())) {
                for (EvalDatasetItem item : items) {
                    addIfPending(workItems, doneKeys, item, model, null);
                }
            }
        }
        return workItems;
    }

    private void addIfPending(List<WorkItem> workItems, Set<String> doneKeys,
                              EvalDatasetItem item, AiModel model, Agent agent) {
        String targetId = model != null ? model.getId() : agent.getId();
        if (!doneKeys.contains(item.getId() + "::" + targetId)) {
            workItems.add(new WorkItem(item, model, agent));
        }
    }

    private EvalResult executeWorkItem(EvalTask task, WorkItem work) {
        return work.model() != null
                ? executeSingle(task, work.item(), work.model())
                : executeTrajectorySingle(task, work.item(), work.agent());
    }

    /** 评估执行组合（被测目标二选一：QA 模型 / TRAJECTORY Agent） */
    record WorkItem(EvalDatasetItem item, AiModel model, Agent agent) {
    }

    /**
     * 测试入口：构建待执行组合（幂等跳过已完成组合）
     */
    List<WorkItem> buildPendingWorkItemsForTest(EvalTask task, List<EvalDatasetItem> items) {
        return buildPendingWorkItems(task, items);
    }

    /**
     * 终态判定：全成功 DONE、全失败 FAILED、混合 PARTIAL（提取为静态方法便于单测）
     */
    static String resolveFinalStatus(long successCount, long failCount) {
        if (successCount == 0) {
            return STATUS_FAILED;
        }
        return failCount == 0 ? STATUS_DONE : STATUS_PARTIAL;
    }

    /**
     * TRAJECTORY 模式单条执行：真实 Agent 执行 → 轨迹采集 → 双模型 Judge 评审
     */
    private EvalResult executeTrajectorySingle(EvalTask task, EvalDatasetItem item, Agent agent) {
        EvalResult result = new EvalResult();
        result.setTenantId(task.getTenantId());
        result.setTaskId(task.getId());
        // TRAJECTORY 模式：被测对象是 Agent，modelId 列存 Agent ID（汇总映射时合并 Agent 名称）
        result.setModelId(agent.getId());
        result.setItemId(item.getId());
        // 快照数据集条目，历史结果不随数据集修改漂移
        result.setInputSnapshot(item.getInputText());
        result.setExpectedSnapshot(item.getExpectedOutput());
        LocalDateTime startedAt = LocalDateTime.now();
        try {
            // metadata 配置下放：systemPrompt/context 作为附加系统上下文注入 Agent 执行
            EvalMetadataConfig metadataConfig = EvalMetadataConfig.from(item.getMetadata());
            List<String> additionalContexts = new ArrayList<>();
            if (metadataConfig.hasSystemPrompt()) {
                additionalContexts.add("【条目级评估提示词】\n" + metadataConfig.systemPrompt());
            }
            if (metadataConfig.hasContext()) {
                additionalContexts.add(metadataConfig.renderContextBlock());
            }
            EvalAgentExecutionBridge.EvalExecutionOutcome outcome = evalAgentExecutionBridge.execute(
                    agent.getId(), item.getInputText(), task.getTenantId(),
                    additionalContexts.isEmpty() ? null : additionalContexts);
            long latency = java.time.Duration.between(startedAt, LocalDateTime.now()).toMillis();
            result.setExecutionId(outcome.getExecutionId());
            result.setOutputText(outcome.getOutputResult());
            result.setLatencyMs(latency);
            result.setOutputLength(outcome.getOutputResult() == null ? 0 : outcome.getOutputResult().length());

            TrajectoryData trajectory = trajectoryCollector.collect(outcome.getExecutionId());
            result.setCost(trajectory != null && trajectory.getCost() != null ? trajectory.getCost() : BigDecimal.ZERO);
            TrajectoryJudge.JudgeOutcome judgeOutcome = trajectoryJudge.judge(
                    task.getJudgeModelIds(), item.getInputText(), item.getExpectedOutput(),
                    trajectoryCollector.renderForJudge(trajectory));
            result.setScore(judgeOutcome.getScore());
            result.setJudgeScore(judgeOutcome.getScore());
            result.setJudgeDetail(buildJudgeDetail(judgeOutcome, outcome, trajectory));

            // 评估管线（执行→采集→评审）完整走通即为 SUCCESS；
            // 执行本身的完成/暂停/失败状态由 judgeDetail.executionStatus 表达，不影响评审结论
            String executionStatus = outcome.getStatus() == null ? "" : outcome.getStatus().toLowerCase(java.util.Locale.ROOT);
            boolean completed = executionStatus.contains("completed");
            if (!completed && !"paused".equals(executionStatus)) {
                log.warn("评估执行未完成: taskId={}, agentId={}, status={}", task.getId(), agent.getId(), outcome.getStatus());
            }
            result.setStatus("SUCCESS");
            return result;
        } catch (Exception e) {
            long latency = java.time.Duration.between(startedAt, LocalDateTime.now()).toMillis();
            result.setLatencyMs(latency);
            result.setStatus("FAILED");
            result.setErrorMessage(e.getMessage());
            result.setScore(0);
            result.setCost(BigDecimal.ZERO);
            result.setOutputLength(0);
            return result;
        }
    }

    private Map<String, Object> buildJudgeDetail(TrajectoryJudge.JudgeOutcome judgeOutcome,
                                                 EvalAgentExecutionBridge.EvalExecutionOutcome outcome,
                                                 TrajectoryData trajectory) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("executionId", outcome.getExecutionId());
        detail.put("conversationId", outcome.getConversationId());
        detail.put("executionStatus", outcome.getStatus());
        detail.put("timedOut", outcome.isTimedOut());
        detail.put("rounds", outcome.getRounds());
        detail.put("hasIssue", judgeOutcome.isHasIssue());
        detail.put("summary", judgeOutcome.getSummary());
        detail.put("consensusFindingCount", judgeOutcome.getConsensusFindingCount());
        detail.put("uniqueFindingCount", judgeOutcome.getUniqueFindingCount());
        detail.put("findings", judgeOutcome.getFindings());
        if (trajectory != null && trajectory.getSpanSummary() != null) {
            Map<String, Object> span = new LinkedHashMap<>();
            span.put("total", trajectory.getSpanSummary().getTotal());
            span.put("failed", trajectory.getSpanSummary().getFailed());
            span.put("recoverable", trajectory.getSpanSummary().getRecoverable());
            span.put("failureCategories", trajectory.getSpanSummary().getFailureCategories());
            detail.put("spanSummary", span);
        }
        return detail;
    }

    private EvalResult executeSingle(EvalTask task, EvalDatasetItem item, AiModel model) {
        EvalResult result = new EvalResult();
        result.setTenantId(task.getTenantId());
        result.setTaskId(task.getId());
        result.setModelId(model.getId());
        result.setItemId(item.getId());
        // 快照数据集条目，历史结果不随数据集修改漂移
        result.setInputSnapshot(item.getInputText());
        result.setExpectedSnapshot(item.getExpectedOutput());
        LocalDateTime startedAt = LocalDateTime.now();
        try {
            // metadata 配置下放：条目级 systemPrompt 覆盖默认；context 作为检索上下文注入
            EvalMetadataConfig metadataConfig = EvalMetadataConfig.from(item.getMetadata());
            String systemPrompt = metadataConfig.hasSystemPrompt()
                    ? metadataConfig.systemPrompt() : EVAL_SYSTEM_PROMPT;
            String contextBlock = metadataConfig.renderContextBlock();
            String userPrompt = contextBlock != null
                    ? contextBlock + "\n\n【用户问题】\n" + item.getInputText()
                    : item.getInputText();

            AiModelConfig config = AiModelConfig.from(model);
            ChatModel chatModel = aiModelRouter.buildChatModel(config);
            ChatResponse response = chatModel.chat(List.of(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(userPrompt)
            ));
            long latency = java.time.Duration.between(startedAt, LocalDateTime.now()).toMillis();
            String outputText = response != null && response.aiMessage() != null ? response.aiMessage().text() : "";
            result.setOutputText(outputText);
            result.setLatencyMs(latency);
            int metricScore = evalMetricCalculator.calculateScore(outputText, item.getExpectedOutput());
            result.setScore(metricScore);
            // Judge 双通道：配置了 Judge 模型时以 LLM Judge 分为主分，规则分作为对照保留
            if (!CollectionUtils.isEmpty(task.getJudgeModelIds())) {
                applyQaJudgeScore(task, item, outputText, metricScore, result);
            }
            result.setStatus("SUCCESS");
            result.setOutputLength(outputText == null ? 0 : outputText.length());
            result.setCost(calculateCost(model, response, outputText));
        } catch (Exception e) {
            long latency = java.time.Duration.between(startedAt, LocalDateTime.now()).toMillis();
            result.setLatencyMs(latency);
            result.setStatus("FAILED");
            result.setErrorMessage(e.getMessage());
            result.setScore(0);
            result.setCost(BigDecimal.ZERO);
            result.setOutputLength(0);
        }
        return result;
    }

    /**
     * QA 模式 Judge 通道：LLM Judge 评分写入 judgeScore/judgeDetail，规则分作为 metricScore 保留
     */
    private void applyQaJudgeScore(EvalTask task, EvalDatasetItem item, String outputText,
                                   int metricScore, EvalResult result) {
        try {
            String userPrompt = buildQaJudgeUserPrompt(item, outputText);
            QualityEvaluationStrategy.EvaluationResult judgeResult = modelBasedQualityEvaluator.evaluate(
                    task.getJudgeModelIds().getFirst(), QA_JUDGE_SYSTEM_PROMPT, userPrompt);
            result.setJudgeScore(judgeResult.score());
            result.setScore(judgeResult.score());
            result.setJudgeDetail(buildQaJudgeDetail(metricScore, judgeResult));
        } catch (Exception e) {
            // Judge 失败不拖垮评估结果：保留规则分，标注 Judge 异常
            log.warn("QA Judge 评分失败: taskId={}, itemId={}", task.getId(), item.getId(), e);
            result.setJudgeDetail(Map.of("metricScore", metricScore, "judgeError", String.valueOf(e.getMessage())));
        }
    }

    private String buildQaJudgeUserPrompt(EvalDatasetItem item, String outputText) {
        StringBuilder builder = new StringBuilder();
        builder.append("【用户问题】\n").append(item.getInputText() == null ? "-" : item.getInputText()).append("\n\n");
        if (StringUtils.hasText(item.getExpectedOutput())) {
            builder.append("【参考基线 / 期望输出】\n").append(item.getExpectedOutput()).append("\n\n");
        }
        builder.append("【被评模型回答】\n").append(StringUtils.hasText(outputText) ? outputText : "（空回答）");
        return builder.toString();
    }

    private Map<String, Object> buildQaJudgeDetail(int metricScore, QualityEvaluationStrategy.EvaluationResult judgeResult) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("metricScore", metricScore);
        detail.put("hasIssue", judgeResult.hasIssue());
        detail.put("summary", judgeResult.summary());
        detail.put("findings", judgeResult.findings().stream().map(finding -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", finding.type());
            map.put("severity", finding.severity());
            map.put("confidence", finding.confidence());
            map.put("title", finding.title());
            map.put("description", finding.description());
            map.put("location", finding.location());
            map.put("suggestion", finding.suggestion());
            return map;
        }).toList());
        return detail;
    }

    private BigDecimal calculateCost(AiModel model, ChatResponse response, String outputText) {
        long inputTokens = response != null && response.metadata() != null && response.metadata().tokenUsage() != null
                ? response.metadata().tokenUsage().inputTokenCount()
                : Math.max(1, outputText == null ? 0 : outputText.length() / 4L);
        long outputTokens = response != null && response.metadata() != null && response.metadata().tokenUsage() != null
                ? response.metadata().tokenUsage().outputTokenCount()
                : Math.max(1, outputText == null ? 0 : outputText.length() / 3L);
        BigDecimal inputPrice = model.getInputPrice() == null ? BigDecimal.ZERO : model.getInputPrice();
        BigDecimal outputPrice = model.getOutputPrice() == null ? BigDecimal.ZERO : model.getOutputPrice();
        return inputPrice.multiply(BigDecimal.valueOf(inputTokens))
                .add(outputPrice.multiply(BigDecimal.valueOf(outputTokens)))
                .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
    }

    @Override
    public EvalTaskVerdictVO verdict(String id, Integer passRateThreshold) {
        EvalTask task = requireTask(id);
        Integer threshold = task.getPassScoreThreshold();
        if (threshold == null) {
            return EvalTaskVerdictVO.builder()
                    .enabled(false)
                    .pass(false)
                    .reason("数据集未配置通过分数线，未启用 PASS/FAIL 判定")
                    .taskStatus(task.getStatus())
                    .build();
        }
        List<EvalResult> results = evalResultMapper.selectList(new LambdaQueryWrapper<EvalResult>()
                .eq(EvalResult::getTaskId, id));
        int total = results.size();
        int passed = (int) results.stream()
                .filter(result -> result.getScore() != null && result.getScore() >= threshold)
                .count();
        int invokeFail = (int) results.stream()
                .filter(result -> !"SUCCESS".equalsIgnoreCase(result.getStatus()))
                .count();
        int below = Math.max(0, total - passed - invokeFail);
        double passRate = total == 0 ? 0D : passed * 100.0 / total;
        // 达标率门槛：默认 100=严格全过门禁；<100 时通过率达标即 PASS
        int rateThreshold = passRateThreshold != null ? Math.min(100, Math.max(1, passRateThreshold)) : 100;
        boolean taskDone = STATUS_DONE.equalsIgnoreCase(task.getStatus());
        boolean pass = taskDone && total > 0 && passRate >= rateThreshold;
        String reason = pass ? null : resolveVerdictFailReason(taskDone, total, below, invokeFail, rateThreshold);
        return EvalTaskVerdictVO.builder()
                .enabled(true)
                .threshold(threshold)
                .pass(pass)
                .reason(reason)
                .taskStatus(task.getStatus())
                .totalCount(total)
                .passedCount(passed)
                .belowThresholdCount(below)
                .invokeFailCount(invokeFail)
                .passRate(passRate)
                .build();
    }

    private String resolveVerdictFailReason(boolean taskDone, int total, int below, int invokeFail,
                                            int rateThreshold) {
        if (!taskDone) {
            return "任务未完成（当前状态不允许通过判定）";
        }
        if (total == 0) {
            return "任务无评估结果";
        }
        return "通过率 " + (total == 0 ? 0 : (int) Math.round((total - below - invokeFail) * 100.0 / total))
                + "% 未达门槛 " + rateThreshold + "%：低于阈值 " + below + " 条，调用失败 " + invokeFail + " 条";
    }

    private EvalTaskVO buildTaskVO(EvalTask task) {
        EvalTaskVO vo = evaluationConverter.toTaskVO(task);
        EvalDataset dataset = evalDatasetMapper.selectById(task.getDatasetId());
        vo.setDatasetName(dataset == null ? task.getDatasetId() : dataset.getName());
        Map<String, String> modelNameMap = new LinkedHashMap<>();
        if (TASK_TYPE_TRAJECTORY.equalsIgnoreCase(task.getTaskType())) {
            // TRAJECTORY 模式被测对象是 Agent，结果行 modelId 存 Agent ID
            List<Agent> agents = requireAgents(task.getAgentIds());
            modelNameMap.putAll(agents.stream()
                    .collect(Collectors.toMap(Agent::getId, Agent::getName, (left, right) -> left)));
            vo.setModelNames(agents.stream().map(Agent::getName).toList());
            vo.setAgentNames(agents.stream().map(Agent::getName).toList());
        } else {
            List<AiModel> models = requireModels(task.getModelIds());
            modelNameMap.putAll(models.stream()
                    .collect(Collectors.toMap(AiModel::getId, AiModel::getName, (left, right) -> left)));
            vo.setModelNames(models.stream().map(AiModel::getName).toList());
        }
        vo.setTaskType(task.getTaskType());
        vo.setAgentIds(task.getAgentIds());
        vo.setJudgeModelIds(task.getJudgeModelIds());
        if (!CollectionUtils.isEmpty(task.getJudgeModelIds())) {
            vo.setJudgeModelNames(requireModels(task.getJudgeModelIds()).stream()
                    .map(AiModel::getName).toList());
        }
        vo.setProgress(task.getTotalItems() == null || task.getTotalItems() == 0
                ? 0D
                : ((task.getCompletedItems() == null ? 0 : task.getCompletedItems()) * 100.0 / task.getTotalItems()));

        List<EvalResult> results = evalResultMapper.selectList(new LambdaQueryWrapper<EvalResult>()
                .eq(EvalResult::getTaskId, task.getId())
                .orderByAsc(EvalResult::getCreatedAt));
        vo.setModelSummaries(buildModelSummaries(results, modelNameMap));
        vo.setResultItems(buildResultItems(results, modelNameMap, loadDatasetItems(task.getDatasetId())));
        // 通过率：按任务快照阈值统计（未启用阈值时 passRate 为 null）
        vo.setPassScoreThreshold(task.getPassScoreThreshold());
        if (task.getPassScoreThreshold() != null && !CollectionUtils.isEmpty(results)) {
            long passed = results.stream()
                    .filter(result -> result.getScore() != null && result.getScore() >= task.getPassScoreThreshold())
                    .count();
            vo.setPassRate(passed * 100.0 / results.size());
        }
        return vo;
    }

    private List<EvalTaskModelSummaryVO> buildModelSummaries(List<EvalResult> results, Map<String, String> modelNameMap) {
        if (CollectionUtils.isEmpty(results)) {
            return List.of();
        }
        Map<String, List<EvalResult>> grouped = results.stream().collect(Collectors.groupingBy(EvalResult::getModelId, LinkedHashMap::new, Collectors.toList()));
        List<EvalTaskModelSummaryVO> summaries = new ArrayList<>();
        for (Map.Entry<String, List<EvalResult>> entry : grouped.entrySet()) {
            List<EvalResult> modelResults = entry.getValue();
            long successCount = modelResults.stream().filter(item -> "SUCCESS".equalsIgnoreCase(item.getStatus())).count();
            EvalTaskModelSummaryVO vo = new EvalTaskModelSummaryVO();
            vo.setModelId(entry.getKey());
            vo.setModelName(modelNameMap.getOrDefault(entry.getKey(), entry.getKey()));
            vo.setAvgLatencyMs(modelResults.stream().filter(item -> item.getLatencyMs() != null).mapToLong(EvalResult::getLatencyMs).average().orElse(0D));
            vo.setAvgCost(modelResults.stream().map(item -> item.getCost() == null ? BigDecimal.ZERO : item.getCost()).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(Math.max(1, modelResults.size())), 6, RoundingMode.HALF_UP));
            vo.setAvgScore(modelResults.stream().filter(item -> item.getScore() != null).mapToInt(EvalResult::getScore).average().orElse(0D));
            vo.setSuccessRate(successCount * 100.0 / Math.max(1, modelResults.size()));
            vo.setAvgOutputLength(modelResults.stream().filter(item -> item.getOutputLength() != null).mapToInt(EvalResult::getOutputLength).average().orElse(0D));
            vo.setInvokeFailCount((int) modelResults.stream().filter(item -> !"SUCCESS".equalsIgnoreCase(item.getStatus())).count());
            summaries.add(vo);
        }
        summaries.sort(Comparator.comparing(EvalTaskModelSummaryVO::getAvgScore, Comparator.nullsLast(Double::compareTo)).reversed());
        return summaries;
    }

    private List<EvalTaskResultItemVO> buildResultItems(List<EvalResult> results,
                                                        Map<String, String> modelNameMap,
                                                        List<EvalDatasetItem> items) {
        if (CollectionUtils.isEmpty(items)) {
            return List.of();
        }
        Map<String, List<EvalResult>> resultMap = results.stream()
                .collect(Collectors.groupingBy(EvalResult::getItemId, LinkedHashMap::new, Collectors.toList()));
        List<EvalTaskResultItemVO> resultItems = new ArrayList<>();
        for (EvalDatasetItem item : items) {
            EvalTaskResultItemVO vo = new EvalTaskResultItemVO();
            vo.setItemId(item.getId());
            // 快照优先：结果行有执行时快照就用快照（历史自洽），否则回退数据集当前值（兼容旧数据）
            List<EvalResult> itemResults = resultMap.getOrDefault(item.getId(), List.of());
            EvalResult snapshotSource = itemResults.stream()
                    .filter(result -> StringUtils.hasText(result.getInputSnapshot()))
                    .findFirst().orElse(null);
            vo.setInputText(snapshotSource != null ? snapshotSource.getInputSnapshot() : item.getInputText());
            vo.setExpectedOutput(snapshotSource != null ? snapshotSource.getExpectedSnapshot() : item.getExpectedOutput());
            List<EvalTaskItemModelResultVO> modelResults = resultMap.getOrDefault(item.getId(), List.of()).stream()
                    .map(result -> {
                        EvalTaskItemModelResultVO modelResult = new EvalTaskItemModelResultVO();
                        modelResult.setModelId(result.getModelId());
                        modelResult.setModelName(modelNameMap.getOrDefault(result.getModelId(), result.getModelId()));
                        modelResult.setOutputText(result.getOutputText());
                        modelResult.setLatencyMs(result.getLatencyMs());
                        modelResult.setCost(result.getCost());
                        modelResult.setScore(result.getScore());
                        modelResult.setStatus(result.getStatus());
                        modelResult.setErrorMessage(result.getErrorMessage());
                        modelResult.setOutputLength(result.getOutputLength());
                        modelResult.setExecutionId(result.getExecutionId());
                        modelResult.setJudgeScore(result.getJudgeScore());
                        modelResult.setJudgeDetail(result.getJudgeDetail());
                        return modelResult;
                    })
                    .sorted(Comparator.comparing(EvalTaskItemModelResultVO::getModelName, Comparator.nullsLast(String::compareTo)))
                    .collect(Collectors.toList());
            vo.setModelResults(modelResults);
            resultItems.add(vo);
        }
        return resultItems;
    }

    private List<EvalDatasetItem> loadDatasetItems(String datasetId) {
        return evalDatasetItemMapper.selectList(new LambdaQueryWrapper<EvalDatasetItem>()
                .eq(EvalDatasetItem::getDatasetId, datasetId)
                .orderByAsc(EvalDatasetItem::getSortOrder)
                .orderByAsc(EvalDatasetItem::getCreatedAt));
    }

    private EvalTask requireTask(String id) {
        EvalTask task = evalTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "评估任务不存在");
        }
        String tenantId = SecurityUtil.getCurrentTenantId();
        if (StringUtils.hasText(tenantId) && !tenantId.equals(task.getTenantId())) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return task;
    }

    private EvalDataset requireDataset(String id) {
        EvalDataset dataset = evalDatasetMapper.selectById(id);
        if (dataset == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "评估数据集不存在");
        }
        String tenantId = SecurityUtil.getCurrentTenantId();
        if (StringUtils.hasText(tenantId) && !tenantId.equals(dataset.getTenantId())) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return dataset;
    }

    private List<AiModel> requireModels(List<String> modelIds) {
        if (CollectionUtils.isEmpty(modelIds)) {
            return List.of();
        }
        List<AiModel> models = aiModelMapper.selectBatchIds(modelIds).stream()
                .filter(Objects::nonNull)
                .toList();
        if (models.size() != modelIds.size()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "存在无效模型配置");
        }
        return models;
    }

    private List<Agent> requireAgents(List<String> agentIds) {
        if (CollectionUtils.isEmpty(agentIds)) {
            return List.of();
        }
        List<Agent> agents = agentMapper.selectBatchIds(agentIds).stream()
                .filter(Objects::nonNull)
                .toList();
        if (agents.size() != agentIds.size()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "存在无效 Agent 配置");
        }
        return agents;
    }
}
