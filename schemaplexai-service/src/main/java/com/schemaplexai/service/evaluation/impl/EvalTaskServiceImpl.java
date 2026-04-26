package com.schemaplexai.service.evaluation.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.EvalDatasetItemMapper;
import com.schemaplexai.dao.mapper.EvalDatasetMapper;
import com.schemaplexai.dao.mapper.EvalResultMapper;
import com.schemaplexai.dao.mapper.EvalTaskMapper;
import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.model.converter.EvaluationConverter;
import com.schemaplexai.model.dto.evaluation.EvalTaskCreateRequest;
import com.schemaplexai.model.dto.evaluation.EvalTaskQueryRequest;
import com.schemaplexai.model.entity.AiModel;
import com.schemaplexai.model.entity.EvalDataset;
import com.schemaplexai.model.entity.EvalDatasetItem;
import com.schemaplexai.model.entity.EvalResult;
import com.schemaplexai.model.entity.EvalTask;
import com.schemaplexai.model.vo.evaluation.EvalTaskItemModelResultVO;
import com.schemaplexai.model.vo.evaluation.EvalTaskModelSummaryVO;
import com.schemaplexai.model.vo.evaluation.EvalTaskResultItemVO;
import com.schemaplexai.model.vo.evaluation.EvalTaskVO;
import com.schemaplexai.service.ai.AIModelRouter;
import com.schemaplexai.service.ai.AiModelConfig;
import com.schemaplexai.service.evaluation.EvalTaskService;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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

    private static final String EVAL_SYSTEM_PROMPT = """
            你是 SchemaPlexAI 的模型评估执行器。
            请根据用户输入直接作答，不要解释评估过程，也不要输出多余前缀。
            如果输入中包含结构化要求，请严格遵守。
            """;

    private final EvalTaskMapper evalTaskMapper;
    private final EvalDatasetMapper evalDatasetMapper;
    private final EvalDatasetItemMapper evalDatasetItemMapper;
    private final EvalResultMapper evalResultMapper;
    private final AiModelMapper aiModelMapper;
    private final EvaluationConverter evaluationConverter;
    private final EvalMetricCalculator evalMetricCalculator;
    private final AIModelRouter aiModelRouter;

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
        List<AiModel> models = requireModels(request.getModelIds());
        EvalTask task = new EvalTask();
        task.setTenantId(SecurityUtil.getCurrentTenantId());
        task.setName(StringUtils.hasText(request.getName()) ? request.getName() : "评估任务-" + LocalDateTime.now());
        task.setDatasetId(dataset.getId());
        task.setModelIds(models.stream().map(AiModel::getId).toList());
        task.setStatus(STATUS_PENDING);
        task.setTotalItems(items.size() * models.size());
        task.setCompletedItems(0);
        evalTaskMapper.insert(task);

        dataset.setLastUsedAt(LocalDateTime.now());
        evalDatasetMapper.updateById(dataset);

        submitAfterCommit(() -> executeTask(task.getId()));
        return getById(task.getId());
    }

    @Override
    public PageResult<EvalTaskVO> page(EvalTaskQueryRequest request) {
        Page<EvalTask> queryPage = new Page<>(request.getPage(), request.getSize());
        LambdaQueryWrapper<EvalTask> wrapper = new LambdaQueryWrapper<>();
        String tenantId = SecurityUtil.getCurrentTenantId();
        if (StringUtils.hasText(tenantId)) {
            wrapper.eq(EvalTask::getTenantId, tenantId);
        }
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq(EvalTask::getStatus, request.getStatus());
        }
        wrapper.orderByDesc(EvalTask::getCreatedAt);
        Page<EvalTask> result = evalTaskMapper.selectPage(queryPage, wrapper);
        List<EvalTaskVO> records = result.getRecords().stream().map(this::buildTaskVO).toList();
        return new PageResult<>(records, result.getTotal(), result.getCurrent(), result.getSize());
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

    private void executeTask(String taskId) {
        EvalTask task = requireTask(taskId);
        EvalDataset dataset = requireDataset(task.getDatasetId());
        List<EvalDatasetItem> items = loadDatasetItems(dataset.getId());
        List<AiModel> models = requireModels(task.getModelIds());
        task.setStatus(STATUS_RUNNING);
        task.setStartedAt(LocalDateTime.now());
        task.setFinishedAt(null);
        task.setErrorMessage(null);
        task.setCompletedItems(0);
        evalTaskMapper.updateById(task);

        boolean hasSuccess = false;
        try {
            for (EvalDatasetItem item : items) {
                for (AiModel model : models) {
                    EvalResult result = executeSingle(task, item, model);
                    evalResultMapper.insert(result);
                    task.setCompletedItems((task.getCompletedItems() == null ? 0 : task.getCompletedItems()) + 1);
                    evalTaskMapper.updateById(task);
                    hasSuccess = hasSuccess || STATUS_DONE.equalsIgnoreCase(result.getStatus()) || "SUCCESS".equalsIgnoreCase(result.getStatus());
                }
            }
            task.setStatus(hasSuccess ? STATUS_DONE : STATUS_FAILED);
            task.setFinishedAt(LocalDateTime.now());
            evalTaskMapper.updateById(task);
        } catch (Exception e) {
            log.error("执行评估任务失败: taskId={}", taskId, e);
            task.setStatus(STATUS_FAILED);
            task.setErrorMessage(e.getMessage());
            task.setFinishedAt(LocalDateTime.now());
            evalTaskMapper.updateById(task);
        }
    }

    private EvalResult executeSingle(EvalTask task, EvalDatasetItem item, AiModel model) {
        EvalResult result = new EvalResult();
        result.setTenantId(task.getTenantId());
        result.setTaskId(task.getId());
        result.setModelId(model.getId());
        result.setItemId(item.getId());
        LocalDateTime startedAt = LocalDateTime.now();
        try {
            AiModelConfig config = AiModelConfig.from(model);
            ChatModel chatModel = aiModelRouter.buildChatModel(config);
            ChatResponse response = chatModel.chat(List.of(
                    SystemMessage.from(EVAL_SYSTEM_PROMPT),
                    UserMessage.from(item.getInputText())
            ));
            long latency = java.time.Duration.between(startedAt, LocalDateTime.now()).toMillis();
            String outputText = response != null && response.aiMessage() != null ? response.aiMessage().text() : "";
            result.setOutputText(outputText);
            result.setLatencyMs(latency);
            result.setScore(evalMetricCalculator.calculateScore(outputText, item.getExpectedOutput()));
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

    private EvalTaskVO buildTaskVO(EvalTask task) {
        EvalTaskVO vo = evaluationConverter.toTaskVO(task);
        EvalDataset dataset = evalDatasetMapper.selectById(task.getDatasetId());
        vo.setDatasetName(dataset == null ? task.getDatasetId() : dataset.getName());
        List<AiModel> models = requireModels(task.getModelIds());
        Map<String, String> modelNameMap = models.stream().collect(Collectors.toMap(AiModel::getId, AiModel::getName, (left, right) -> left));
        vo.setModelNames(models.stream().map(AiModel::getName).toList());
        vo.setProgress(task.getTotalItems() == null || task.getTotalItems() == 0
                ? 0D
                : ((task.getCompletedItems() == null ? 0 : task.getCompletedItems()) * 100.0 / task.getTotalItems()));

        List<EvalResult> results = evalResultMapper.selectList(new LambdaQueryWrapper<EvalResult>()
                .eq(EvalResult::getTaskId, task.getId())
                .orderByAsc(EvalResult::getCreatedAt));
        vo.setModelSummaries(buildModelSummaries(results, modelNameMap));
        vo.setResultItems(buildResultItems(results, modelNameMap, loadDatasetItems(task.getDatasetId())));
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
            vo.setInputText(item.getInputText());
            vo.setExpectedOutput(item.getExpectedOutput());
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
}
