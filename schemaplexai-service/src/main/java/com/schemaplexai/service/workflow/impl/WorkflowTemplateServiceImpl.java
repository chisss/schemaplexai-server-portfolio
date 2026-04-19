package com.schemaplexai.service.workflow.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.enums.AgentStatusEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.enums.WorkflowInstanceStatusEnum;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.dao.mapper.WorkflowInstanceMapper;
import com.schemaplexai.dao.mapper.WorkflowTemplateMapper;
import com.schemaplexai.model.converter.WorkflowTemplateConverter;
import com.schemaplexai.model.dto.agent.AgentExecuteDTO;
import com.schemaplexai.model.dto.workflow.WorkflowAiArrangeRequest;
import com.schemaplexai.model.dto.workflow.WorkflowTemplateCreateRequest;
import com.schemaplexai.model.dto.workflow.WorkflowTemplateQueryRequest;
import com.schemaplexai.model.dto.workflow.WorkflowTemplateUpdateRequest;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowTemplate;
import com.schemaplexai.model.vo.workflow.WorkflowAiArrangeVO;
import com.schemaplexai.model.vo.workflow.WorkflowTemplateStatsVO;
import com.schemaplexai.model.vo.workflow.WorkflowTemplateVO;
import com.schemaplexai.service.agent.execution.AgentExecutionResult;
import com.schemaplexai.service.agent.runtime.AgentRuntimeOrchestrator;
import com.schemaplexai.service.workflow.WorkflowTemplateService;
import com.schemaplexai.service.workflow.flowable.FlowableDeploymentService;
import com.schemaplexai.service.workflow.validator.WorkflowValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作流模板服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowTemplateServiceImpl implements WorkflowTemplateService {

    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("(?is)```(?:json)?\\s*(\\{.*?})\\s*```");

    private final AgentMapper agentMapper;
    private final SpecMapper specMapper;
    private final WorkflowTemplateMapper templateMapper;
    private final WorkflowInstanceMapper instanceMapper;
    private final WorkflowTemplateConverter templateConverter;
    private final WorkflowValidator workflowValidator;
    private final FlowableDeploymentService flowableDeploymentService;
    private final AgentRuntimeOrchestrator agentRuntimeOrchestrator;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkflowTemplateVO create(WorkflowTemplateCreateRequest request) {
        workflowValidator.validateDefinitionFormat(request.getDefinition());

        var template = templateConverter.fromCreateRequest(request);
        template.setTriggerType(extractTriggerType(request.getDefinition()));
        templateMapper.insert(template);

        // 部署到 Flowable
        deployToFlowable(template);

        log.info("创建工作流模板成功: templateId={}, name={}", template.getId(), template.getName());
        return templateConverter.toVO(templateMapper.selectById(template.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkflowTemplateVO update(String id, WorkflowTemplateUpdateRequest request) {
        var template = requireExists(id);
        workflowValidator.validateNotBuiltin(template);

        if (request.getDefinition() != null) {
            workflowValidator.validateDefinitionFormat(request.getDefinition());
        }

        var updateEntity = new WorkflowTemplate();
        updateEntity.setId(id);
        if (StringUtils.hasText(request.getName())) {
            updateEntity.setName(request.getName());
        }
        if (request.getDescription() != null) {
            updateEntity.setDescription(request.getDescription());
        }
        if (StringUtils.hasText(request.getCategory())) {
            updateEntity.setCategory(request.getCategory());
        }
        if (request.getDefinition() != null) {
            updateEntity.setDefinition(request.getDefinition());
            updateEntity.setTriggerType(extractTriggerType(request.getDefinition()));
        }
        if (request.getRecommendedAgentSkills() != null) {
            updateEntity.setRecommendedAgentSkills(request.getRecommendedAgentSkills());
        }
        updateEntity.setUpdatedAt(LocalDateTime.now());
        templateMapper.updateById(updateEntity);

        // definition 变更时重新部署到 Flowable
        if (request.getDefinition() != null) {
            var updatedTemplate = templateMapper.selectById(id);
            deployToFlowable(updatedTemplate);
        }

        log.info("更新工作流模板成功: templateId={}", id);
        return templateConverter.toVO(templateMapper.selectById(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        var template = requireExists(id);
        workflowValidator.validateNotBuiltin(template);

        // 校验无实例引用
        var instanceCount = instanceMapper.selectCount(
                new LambdaQueryWrapper<WorkflowInstance>()
                        .eq(WorkflowInstance::getTemplateId, id)
        );
        if (instanceCount > 0) {
            throw new BusinessException(ResultCode.WORKFLOW_TEMPLATE_IN_USE);
        }

        templateMapper.deleteById(id);
        log.info("删除工作流模板成功: templateId={}", id);
    }

    @Override
    public WorkflowTemplateVO getById(String id) {
        var template = requireExists(id);
        return templateConverter.toVO(template);
    }

    @Override
    public PageResult<WorkflowTemplateVO> page(WorkflowTemplateQueryRequest query) {
        var page = new Page<WorkflowTemplate>(query.getPage(), query.getSize());
        var wrapper = new LambdaQueryWrapper<WorkflowTemplate>();

        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.and(w -> w
                    .like(WorkflowTemplate::getName, query.getKeyword())
                    .or()
                    .like(WorkflowTemplate::getDescription, query.getKeyword())
            );
        }
        if (StringUtils.hasText(query.getCategory())) {
            wrapper.eq(WorkflowTemplate::getCategory, query.getCategory());
        }
        if (query.getIsBuiltin() != null) {
            wrapper.eq(WorkflowTemplate::getIsBuiltin, query.getIsBuiltin());
        }
        wrapper.orderByDesc(WorkflowTemplate::getCreatedAt);

        var result = templateMapper.selectPage(page, wrapper);
        var voList = templateConverter.toVOList(result.getRecords());
        return new PageResult<>(voList, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public List<WorkflowTemplateVO> listAll() {
        var wrapper = new LambdaQueryWrapper<WorkflowTemplate>()
                .orderByDesc(WorkflowTemplate::getCreatedAt);
        return templateConverter.toVOList(templateMapper.selectList(wrapper));
    }

    /**
     * 部署模板到 Flowable 引擎，回填 processDefinitionId
     */
    private void deployToFlowable(WorkflowTemplate template) {
        if (template.getDefinition() == null || template.getDefinition().isEmpty()) {
            return;
        }
        try {
            String processDefinitionId = flowableDeploymentService.deployTemplate(template);
            if (StringUtils.hasText(processDefinitionId)) {
                var update = new WorkflowTemplate();
                update.setId(template.getId());
                update.setProcessDefinitionId(processDefinitionId);
                update.setUpdatedAt(LocalDateTime.now());
                templateMapper.updateById(update);
                log.info("模板部署到 Flowable 成功: templateId={}, processDefinitionId={}",
                        template.getId(), processDefinitionId);
            }
        } catch (Exception e) {
            log.error("模板部署到 Flowable 失败: templateId={}, error={}", template.getId(), e.getMessage(), e);
            // 部署失败不阻塞模板保存，降级使用原有引擎
        }
    }

    private static final Set<String> TRIGGER_TYPES = Set.of(
            "trigger_manual", "trigger_cron", "trigger_event"
    );

    /**
     * 从 definition 的 nodes 中提取第一个触发节点的类型
     */
    @SuppressWarnings("unchecked")
    private String extractTriggerType(Map<String, Object> definition) {
        if (definition == null) {
            return null;
        }
        var nodes = definition.get("nodes");
        if (!(nodes instanceof List<?> nodeList)) {
            return null;
        }
        for (Object node : nodeList) {
            if (node instanceof Map<?, ?> nodeMap) {
                var type = String.valueOf(nodeMap.get("type"));
                if (TRIGGER_TYPES.contains(type)) {
                    return type;
                }
            }
        }
        return null;
    }

    private WorkflowTemplate requireExists(String id) {
        var template = templateMapper.selectById(id);
        if (template == null) {
            throw new BusinessException(ResultCode.WORKFLOW_NOT_FOUND);
        }
        return template;
    }

    @Override
    public WorkflowAiArrangeVO aiArrange(String templateId, WorkflowAiArrangeRequest request) {
        WorkflowTemplate template = requireExists(templateId);
        Spec spec = StringUtils.hasText(request.getSpecId()) ? specMapper.selectById(request.getSpecId()) : null;
        Agent arrangeAgent = findArrangeAgent(SecurityUtil.getCurrentTenantId());
        Map<String, Object> definition;
        String explanation;
        String executionId = null;

        if (arrangeAgent != null) {
            AgentExecuteDTO executeDTO = new AgentExecuteDTO();
            executeDTO.setPrompt(buildArrangePrompt(template, spec, request.getPrompt()));
            executeDTO.setConversationId(UUID.randomUUID().toString().replace("-", ""));
            executeDTO.setOutputFormat("structured_json");
            executeDTO.setReasoningStrength("high");

            AgentExecutionResult executionResult = agentRuntimeOrchestrator.executeSynchronously(arrangeAgent, executeDTO);
            executionId = executeDTO.getConversationId();
            definition = normalizeArrangeDefinition(parseArrangeDefinition(executionResult.getOutputResult(), request.getPrompt()), arrangeAgent);
            explanation = resolveArrangeExplanation(executionResult.getOutputResult(), false);
        } else {
            definition = normalizeArrangeDefinition(buildFallbackDefinition(request.getPrompt()), resolveDefaultExecutionAgent());
            explanation = resolveArrangeExplanation(null, true);
            log.warn("当前租户未找到工作流编排 Agent，已回退到内置编排器: tenantId={}", SecurityUtil.getCurrentTenantId());
        }

        WorkflowAiArrangeVO result = new WorkflowAiArrangeVO();
        result.setSuggestedDefinition(definition);
        result.setNodes(castNodeOrEdgeList(definition.get("nodes")));
        result.setEdges(castNodeOrEdgeList(definition.get("edges")));
        result.setExecutionId(executionId);
        result.setExplanation(explanation);
        result.setStatus(WorkflowInstanceStatusEnum.COMPLETED.getCode());
        return result;
    }

    private Agent findArrangeAgent(String tenantId) {
        return agentMapper.selectOne(new LambdaQueryWrapper<Agent>()
                .eq(StringUtils.hasText(tenantId), Agent::getTenantId, tenantId)
                .eq(Agent::getStatus, AgentStatusEnum.ACTIVE.getCode())
                .and(wrapper -> wrapper
                        .like(Agent::getAgentTag, "workflow_arrange")
                        .or()
                        .like(Agent::getName, "工作流编排")
                        .or()
                        .like(Agent::getName, "Workflow Arrange"))
                .last("LIMIT 1"));
    }

    private Agent resolveDefaultExecutionAgent() {
        return agentMapper.selectOne(new LambdaQueryWrapper<Agent>()
                .eq(StringUtils.hasText(SecurityUtil.getCurrentTenantId()), Agent::getTenantId, SecurityUtil.getCurrentTenantId())
                .eq(Agent::getStatus, AgentStatusEnum.ACTIVE.getCode())
                .orderByAsc(Agent::getCreatedAt)
                .last("LIMIT 1"));
    }

    private String buildArrangePrompt(WorkflowTemplate template, Spec spec, String userPrompt) {
        StringBuilder builder = new StringBuilder();
        builder.append("你是 SchemaPlexAI 的工作流编排助手，请输出单个 JSON 对象。")
                .append(" JSON 结构必须为 {\"nodes\": [...], \"edges\": [...], \"explanation\": \"...\"}。")
                .append(" nodes 中每个节点必须包含 id、type、label、position、config 字段；")
                .append(" edges 中每条边必须包含 id、source、target。")
                .append(" 可用节点类型仅限 trigger_manual、agent、human_review、document、condition、end。")
                .append(" 所有 agent 节点 config.instructionSource 必须为 \"upstream\"。");
        if (template != null) {
            builder.append("\n模板名称: ").append(template.getName());
            if (StringUtils.hasText(template.getDescription())) {
                builder.append("\n模板说明: ").append(template.getDescription());
            }
        }
        if (spec != null) {
            builder.append("\n关联 Spec: ").append(spec.getName());
            if (StringUtils.hasText(spec.getDescription())) {
                builder.append("\nSpec 描述: ").append(spec.getDescription());
            }
            if (StringUtils.hasText(spec.getCategory())) {
                builder.append("\nSpec 分类: ").append(spec.getCategory());
            }
        }
        builder.append("\n用户需求: ").append(userPrompt);
        return builder.toString();
    }

    private Map<String, Object> parseArrangeDefinition(String rawOutput, String originalPrompt) {
        if (!StringUtils.hasText(rawOutput)) {
            return buildFallbackDefinition(originalPrompt);
        }
        try {
            String candidate = extractJsonPayload(rawOutput);
            JsonNode root = objectMapper.readTree(candidate);
            JsonNode definitionNode = root.has("suggestedDefinition") ? root.get("suggestedDefinition") : root;
            JsonNode nodesNode = definitionNode.get("nodes");
            JsonNode edgesNode = definitionNode.get("edges");
            if (nodesNode == null || !nodesNode.isArray() || edgesNode == null || !edgesNode.isArray()) {
                return buildFallbackDefinition(originalPrompt);
            }
            Map<String, Object> definition = new LinkedHashMap<>();
            definition.put("nodes", objectMapper.convertValue(nodesNode, List.class));
            definition.put("edges", objectMapper.convertValue(edgesNode, List.class));
            return definition;
        } catch (Exception exception) {
            log.warn("解析 AI 编排输出失败，已回退到内置编排器: error={}", exception.getMessage());
            return buildFallbackDefinition(originalPrompt);
        }
    }

    private String resolveArrangeExplanation(String rawOutput, boolean fallbackUsed) {
        if (fallbackUsed) {
            return "未命中工作流编排 Agent，已根据当前描述生成可编辑的基础流程草图。";
        }
        if (!StringUtils.hasText(rawOutput)) {
            return "AI 已生成流程草图，请继续检查并保存。";
        }
        try {
            String candidate = extractJsonPayload(rawOutput);
            JsonNode root = objectMapper.readTree(candidate);
            JsonNode explanationNode = root.get("explanation");
            if (explanationNode != null && explanationNode.isTextual() && StringUtils.hasText(explanationNode.asText())) {
                return explanationNode.asText();
            }
        } catch (Exception exception) {
            log.debug("解析 AI 编排说明失败，使用默认说明: {}", exception.getMessage());
        }
        return "AI 已根据当前描述生成流程草图，请继续检查并保存。";
    }

    private String extractJsonPayload(String rawOutput) {
        Matcher matcher = JSON_BLOCK_PATTERN.matcher(rawOutput);
        if (matcher.find()) {
            return matcher.group(1);
        }
        int start = rawOutput.indexOf('{');
        int end = rawOutput.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return rawOutput.substring(start, end + 1);
        }
        return rawOutput;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castNodeOrEdgeList(Object source) {
        if (source instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    private Map<String, Object> normalizeArrangeDefinition(Map<String, Object> definition, Agent defaultAgent) {
        List<Map<String, Object>> rawNodes = castNodeOrEdgeList(definition.get("nodes"));
        List<Map<String, Object>> normalizedNodes = new ArrayList<>();
        for (Map<String, Object> rawNode : rawNodes) {
            Map<String, Object> normalizedNode = new LinkedHashMap<>(rawNode);
            if ("agent".equals(String.valueOf(normalizedNode.get("type")))) {
                Map<String, Object> config = normalizedNode.get("config") instanceof Map<?, ?> configMap
                        ? new LinkedHashMap<>((Map<String, Object>) configMap)
                        : new LinkedHashMap<>();
                config.putIfAbsent("instructionSource", "upstream");
                config.putIfAbsent("executionStrategy", "short_wait");
                config.putIfAbsent("blockOnQualityTaskFailed", false);
                Object agentId = config.get("agentId");
                boolean missingAgentId = !(agentId instanceof String agentIdText) || !StringUtils.hasText(agentIdText);
                if (defaultAgent != null && missingAgentId) {
                    config.put("agentId", defaultAgent.getId());
                }
                normalizedNode.put("config", config);
            }
            normalizedNodes.add(normalizedNode);
        }
        Map<String, Object> normalizedDefinition = new LinkedHashMap<>(definition);
        normalizedDefinition.put("nodes", normalizedNodes);
        normalizedDefinition.put("edges", castNodeOrEdgeList(definition.get("edges")));
        return normalizedDefinition;
    }

    private Map<String, Object> buildFallbackDefinition(String prompt) {
        List<String> stepLabels = extractFallbackSteps(prompt);
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();

        nodes.add(buildNode("trigger_manual_1", "trigger_manual", "手动触发", 120, 220, Map.of("mode", "manual")));
        String previousNodeId = "trigger_manual_1";
        int currentX = 360;
        for (int index = 0; index < stepLabels.size(); index++) {
            String label = stepLabels.get(index);
            String nodeId = "arrange_step_" + (index + 1);
            String nodeType = resolveFallbackNodeType(label);
            nodes.add(buildNode(nodeId, nodeType, label, currentX, 220, buildFallbackNodeConfig(nodeType, label, index)));
            edges.add(buildEdge(previousNodeId + "_to_" + nodeId, previousNodeId, nodeId));
            previousNodeId = nodeId;
            currentX += 240;
        }
        nodes.add(buildNode("end_1", "end", "结束", currentX, 220, Map.of()));
        edges.add(buildEdge(previousNodeId + "_to_end_1", previousNodeId, "end_1"));

        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("nodes", nodes);
        definition.put("edges", edges);
        return definition;
    }

    private List<String> extractFallbackSteps(String prompt) {
        if (!StringUtils.hasText(prompt)) {
            return List.of("需求分析", "方案设计", "人工复核");
        }
        String normalized = prompt.replace("→", "->")
                .replace("—>", "->")
                .replace("=>", "->")
                .replace("，", "\n")
                .replace("；", "\n");
        List<String> steps = java.util.Arrays.stream(normalized.split("->|\\n"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(item -> item.replaceFirst("^[0-9]+[.)、\\s]+", ""))
                .filter(StringUtils::hasText)
                .limit(5)
                .toList();
        if (steps.size() >= 2) {
            return steps;
        }
        return List.of("需求分析", "Agent 处理", "人工复核");
    }

    private String resolveFallbackNodeType(String label) {
        String normalized = label.toLowerCase();
        if (normalized.contains("审核") || normalized.contains("review") || normalized.contains("审批")) {
            return "human_review";
        }
        if (normalized.contains("文档") || normalized.contains("报告") || normalized.contains("交付")) {
            return "document";
        }
        if (normalized.contains("判断") || normalized.contains("条件") || normalized.contains("分支")) {
            return "condition";
        }
        return "agent";
    }

    private Map<String, Object> buildFallbackNodeConfig(String nodeType, String label, int index) {
        if ("human_review".equals(nodeType)) {
            return Map.of(
                    "reviewerType", "internal",
                    "timeoutStrategy", "remind"
            );
        }
        if ("document".equals(nodeType)) {
            String documentKey = "delivery_doc_" + (index + 1);
            return Map.of(
                    "documentKey", documentKey,
                    "documentTitle", label,
                    "submitButtonText", "提交" + label
            );
        }
        if ("condition".equals(nodeType)) {
            return Map.of(
                    "trueLabel", "是",
                    "falseLabel", "否"
            );
        }
        return Map.of(
                "instructionSource", "upstream",
                "executionStrategy", "short_wait",
                "blockOnQualityTaskFailed", false
        );
    }

    private Map<String, Object> buildNode(String id, String type, String label, int x, int y, Map<String, Object> config) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", type);
        node.put("label", label);
        node.put("position", Map.of("x", x, "y", y));
        node.put("config", config);
        return node;
    }

    private Map<String, Object> buildEdge(String id, String source, String target) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", id);
        edge.put("source", source);
        edge.put("target", target);
        return edge;
    }

    @Override
    public WorkflowTemplateStatsVO getStats() {
        // 统计 published 状态的模板数量
        var activeCount = templateMapper.selectCount(
                new LambdaQueryWrapper<WorkflowTemplate>()
                        .eq(WorkflowTemplate::getStatus, "published")
        );

        // 统计24小时内的实例运行数和成功率
        var since = LocalDateTime.now().minusHours(24);
        var recentInstances = instanceMapper.selectList(
                new LambdaQueryWrapper<WorkflowInstance>()
                        .ge(WorkflowInstance::getCreatedAt, since)
        );
        long totalRuns = recentInstances.size();
        long completedRuns = recentInstances.stream()
                .filter(i -> WorkflowInstanceStatusEnum.COMPLETED.getCode().equals(i.getStatus()))
                .count();
        double avgSuccessRate = totalRuns > 0 ? (completedRuns * 100.0 / totalRuns) : 0.0;

        var stats = new WorkflowTemplateStatsVO();
        stats.setActiveWorkflows(activeCount);
        stats.setAvgSuccessRate(Math.round(avgSuccessRate * 10) / 10.0);
        stats.setTotalRuns24h(totalRuns);
        return stats;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkflowTemplateVO toggleStatus(String id) {
        var template = requireExists(id);
        String currentStatus = template.getStatus();
        // published → archived, 其他 → published
        String newStatus = "published".equals(currentStatus) ? "archived" : "published";

        var updateEntity = new WorkflowTemplate();
        updateEntity.setId(id);
        updateEntity.setStatus(newStatus);
        updateEntity.setUpdatedAt(LocalDateTime.now());
        templateMapper.updateById(updateEntity);

        // published 时确保已部署到 Flowable
        if ("published".equals(newStatus)) {
            var updatedTemplate = templateMapper.selectById(id);
            if (!StringUtils.hasText(updatedTemplate.getProcessDefinitionId())) {
                deployToFlowable(updatedTemplate);
            }
        }

        log.info("切换工作流模板状态: templateId={}, {} -> {}", id, currentStatus, newStatus);
        return templateConverter.toVO(templateMapper.selectById(id));
    }
}
