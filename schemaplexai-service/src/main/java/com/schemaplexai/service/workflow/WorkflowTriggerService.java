package com.schemaplexai.service.workflow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.WorkflowInstanceMapper;
import com.schemaplexai.dao.mapper.WorkflowTemplateMapper;
import com.schemaplexai.model.dto.workflow.ManualTriggerRequest;
import com.schemaplexai.model.dto.workflow.TriggerConfigUpdateRequest;
import com.schemaplexai.model.dto.workflow.WorkflowInstanceCreateRequest;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowTemplate;
import com.schemaplexai.model.vo.workflow.ManualTriggerResultVO;
import com.schemaplexai.model.vo.workflow.TriggerConfigVO;
import com.schemaplexai.model.vo.workflow.TriggerStatsVO;
import com.schemaplexai.model.vo.workflow.WorkflowInstanceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 工作流触发管理服务
 * <p>从 definition JSON 中提取触发节点配置，提供统一的触发管理能力。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowTriggerService {

    private static final DateTimeFormatter INSTANCE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final WorkflowTemplateMapper workflowTemplateMapper;
    private final WorkflowInstanceMapper workflowInstanceMapper;
    private final ObjectProvider<WorkflowInstanceService> workflowInstanceServiceProvider;

    /**
     * 分页查询触发配置列表
     */
    public PageResult<TriggerConfigVO> pageTriggerConfigs(String keyword, String triggerType,
                                                          int page, int size) {
        String tenantId = SecurityUtil.getCurrentTenantId();
        LambdaQueryWrapper<WorkflowTemplate> wrapper = new LambdaQueryWrapper<WorkflowTemplate>()
                .eq(WorkflowTemplate::getTenantId, tenantId)
                .ne(WorkflowTemplate::getStatus, "archived")
                .like(StringUtils.hasText(keyword), WorkflowTemplate::getName, keyword)
                .eq(StringUtils.hasText(triggerType), WorkflowTemplate::getTriggerType, triggerType)
                .orderByDesc(WorkflowTemplate::getUpdatedAt);

        Page<WorkflowTemplate> pageResult = workflowTemplateMapper.selectPage(
                new Page<>(page, size), wrapper);

        List<TriggerConfigVO> records = pageResult.getRecords().stream()
                .map(this::buildTriggerConfigVO)
                .collect(Collectors.toList());

        return new PageResult<>(records, pageResult.getTotal(), pageResult.getCurrent(), pageResult.getSize());
    }

    /**
     * 获取单个模板的触发配置
     */
    public TriggerConfigVO getTriggerConfig(String templateId) {
        WorkflowTemplate template = requireTemplate(templateId);
        return buildTriggerConfigVO(template);
    }

    private static final Set<String> VALID_TRIGGER_TYPES = Set.of(
            "trigger_manual", "trigger_cron", "trigger_event"
    );

    /**
     * 更新触发节点配置，支持切换触发类型
     */
    public TriggerConfigVO updateTriggerConfig(String templateId, TriggerConfigUpdateRequest request) {
        WorkflowTemplate template = requireTemplate(templateId);
        Map<String, Object> triggerNode = findTriggerNode(template);
        if (triggerNode == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该工作流无触发节点");
        }

        Map<String, Object> config = ensureMutableConfig(triggerNode);

        // 切换触发类型
        if (StringUtils.hasText(request.getTriggerType())) {
            String newType = request.getTriggerType();
            if (!VALID_TRIGGER_TYPES.contains(newType)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的触发类型: " + newType);
            }
            String oldType = template.getTriggerType();
            if (!newType.equals(oldType)) {
                updateTriggerNodeTypeInDefinition(template, newType);
                template.setTriggerType(newType);
                // 清理不适用的旧配置
                if (!"trigger_cron".equals(newType)) {
                    config.remove("cronExpression");
                    config.remove("timezone");
                }
                if (!"trigger_event".equals(newType)) {
                    config.remove("eventType");
                    config.remove("eventHook");
                    config.remove("eventFilter");
                }
                log.info("触发类型已切换: templateId={}, {} -> {}", templateId, oldType, newType);
            }
        }

        if (request.getCronExpression() != null) {
            config.put("cronExpression", request.getCronExpression());
        }
        if (request.getTimezone() != null) {
            config.put("timezone", request.getTimezone());
        }
        if (request.getEventType() != null) {
            config.put("eventType", request.getEventType());
        }
        if (request.getEventHook() != null) {
            config.put("eventHook", request.getEventHook());
        }
        if (request.getEventFilter() != null) {
            config.put("eventFilter", request.getEventFilter());
        }
        if (request.getTriggerInputs() != null) {
            config.put("triggerInputs", request.getTriggerInputs());
        }
        if (request.getEnabled() != null) {
            config.put("enabled", request.getEnabled());
        }
        validateTriggerConfig(template.getTriggerType(), config);

        triggerNode.put("config", config);
        template.setUpdatedAt(LocalDateTime.now());
        workflowTemplateMapper.updateById(template);

        log.info("触发配置已更新: templateId={}, triggerType={}", templateId, template.getTriggerType());
        return buildTriggerConfigVO(template);
    }

    /**
     * 启停触发
     */
    public TriggerConfigVO toggleTrigger(String templateId, boolean enabled) {
        TriggerConfigUpdateRequest request = new TriggerConfigUpdateRequest();
        request.setEnabled(enabled);
        return updateTriggerConfig(templateId, request);
    }

    /**
     * 手动触发工作流
     */
    public ManualTriggerResultVO fireTrigger(String templateId, ManualTriggerRequest request) {
        WorkflowTemplate template = requireTemplate(templateId);

        String instanceName = StringUtils.hasText(request.getName())
                ? request.getName()
                : template.getName() + " - " + INSTANCE_TIME_FORMATTER.format(LocalDateTime.now()) + " 手动触发";

        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("triggerType", "manual");
        variables.put("triggerBy", SecurityUtil.getCurrentUserId());
        variables.put("triggeredAt", LocalDateTime.now().toString());
        if (request.getVariables() != null) {
            variables.putAll(request.getVariables());
        }

        WorkflowInstanceCreateRequest createRequest = new WorkflowInstanceCreateRequest();
        createRequest.setTemplateId(templateId);
        createRequest.setTenantId(SecurityUtil.getCurrentTenantId());
        createRequest.setName(instanceName);
        createRequest.setVariables(variables);

        WorkflowInstanceService instanceService = workflowInstanceServiceProvider.getObject();
        WorkflowInstanceVO created = instanceService.create(createRequest);
        WorkflowInstanceVO started = instanceService.start(created.getId());

        log.info("手动触发成功: templateId={}, instanceId={}", templateId, started.getId());
        return ManualTriggerResultVO.builder()
                .instanceId(started.getId())
                .instanceName(started.getName())
                .status(started.getStatus())
                .build();
    }

    /**
     * 最近执行记录
     */
    public List<WorkflowInstanceVO> recentExecutions(String templateId, int limit) {
        List<WorkflowInstance> instances = workflowInstanceMapper.selectList(
                new LambdaQueryWrapper<WorkflowInstance>()
                        .eq(WorkflowInstance::getTemplateId, templateId)
                        .orderByDesc(WorkflowInstance::getCreatedAt)
                        .last("LIMIT " + Math.min(limit, 20)));

        return instances.stream().map(this::toSimpleVO).collect(Collectors.toList());
    }

    /**
     * 触发统计
     */
    public TriggerStatsVO getStats() {
        String tenantId = SecurityUtil.getCurrentTenantId();
        List<WorkflowTemplate> templates = workflowTemplateMapper.selectList(
                new LambdaQueryWrapper<WorkflowTemplate>()
                        .eq(WorkflowTemplate::getTenantId, tenantId)
                        .ne(WorkflowTemplate::getStatus, "archived"));

        int manualCount = 0, cronCount = 0, cronEnabled = 0, eventCount = 0, eventEnabled = 0;
        for (WorkflowTemplate template : templates) {
            String type = template.getTriggerType();
            if ("trigger_cron".equals(type)) {
                cronCount++;
                if (isTriggerEnabled(template)) cronEnabled++;
            } else if ("trigger_event".equals(type)) {
                eventCount++;
                if (isTriggerEnabled(template)) eventEnabled++;
            } else {
                manualCount++;
            }
        }

        return TriggerStatsVO.builder()
                .manualCount(manualCount)
                .cronCount(cronCount)
                .cronEnabledCount(cronEnabled)
                .eventCount(eventCount)
                .eventEnabledCount(eventEnabled)
                .build();
    }

    // ── 内部方法 ──

    private TriggerConfigVO buildTriggerConfigVO(WorkflowTemplate template) {
        Map<String, Object> triggerNode = findTriggerNode(template);
        Map<String, Object> config = triggerNode != null ? getConfig(triggerNode) : Map.of();

        String triggerType = template.getTriggerType();
        if (!StringUtils.hasText(triggerType) && triggerNode != null) {
            triggerType = readString(triggerNode, "type");
        }

        // 最近一次执行
        WorkflowInstance lastInstance = workflowInstanceMapper.selectOne(
                new LambdaQueryWrapper<WorkflowInstance>()
                        .eq(WorkflowInstance::getTemplateId, template.getId())
                        .orderByDesc(WorkflowInstance::getCreatedAt)
                        .last("LIMIT 1"));

        String cronExpr = readString(config, "cronExpression");

        return TriggerConfigVO.builder()
                .templateId(template.getId())
                .templateName(template.getName())
                .category(template.getCategory())
                .triggerType(triggerType)
                .templateStatus(template.getStatus())
                .enabled(Boolean.TRUE.equals(config.get("enabled")))
                .cronExpression(cronExpr)
                .timezone(readString(config, "timezone"))
                .cronHumanReadable(describeCron(cronExpr))
                .eventType(readString(config, "eventType"))
                .eventHook(readString(config, "eventHook"))
                .eventFilter(readString(config, "eventFilter"))
                .triggerInputs(resolveTriggerInputsMap(config))
                .lastTriggeredAt(lastInstance != null ? lastInstance.getStartedAt() : null)
                .lastTriggerStatus(lastInstance != null ? lastInstance.getStatus() : null)
                .build();
    }

    /**
     * 直接修改 definition 中触发节点的 type（原地更新，非副本）
     */
    @SuppressWarnings("unchecked")
    private void updateTriggerNodeTypeInDefinition(WorkflowTemplate template, String newType) {
        if (template.getDefinition() == null) return;
        Object rawNodes = template.getDefinition().get("nodes");
        if (!(rawNodes instanceof List<?> nodes)) return;
        for (Object rawNode : nodes) {
            if (rawNode instanceof Map<?, ?> rawMap) {
                String type = String.valueOf(rawMap.get("type"));
                if (type.startsWith("trigger_") || "start".equals(type)) {
                    ((Map<String, Object>) rawMap).put("type", newType);
                    return;
                }
            }
        }
    }

    private Map<String, Object> findTriggerNode(WorkflowTemplate template) {
        if (template == null || template.getDefinition() == null) return null;
        Object rawNodes = template.getDefinition().get("nodes");
        if (!(rawNodes instanceof List<?> nodes)) return null;
        for (Object rawNode : nodes) {
            if (rawNode instanceof Map<?, ?> rawMap) {
                Object typeObj = rawMap.get("type");
                String type = typeObj != null ? String.valueOf(typeObj) : "";
                if (type.startsWith("trigger_") || "start".equals(type)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> node = (Map<String, Object>) rawMap;
                    return node;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ensureMutableConfig(Map<String, Object> node) {
        Object raw = node.get("config");
        if (raw instanceof Map<?, ?> rawMap) {
            if (raw instanceof LinkedHashMap<?, ?>) {
                return (Map<String, Object>) raw;
            }
            Map<String, Object> config = new LinkedHashMap<>();
            rawMap.forEach((k, v) -> config.put(String.valueOf(k), v));
            node.put("config", config);
            return config;
        }
        Object data = node.get("data");
        if (data instanceof Map<?, ?> dataMap) {
            Object dataConfig = dataMap.get("config");
            if (dataConfig instanceof Map<?, ?> dcMap) {
                Map<String, Object> config = new LinkedHashMap<>();
                dcMap.forEach((k, v) -> config.put(String.valueOf(k), v));
                node.put("config", config);
                return config;
            }
        }
        Map<String, Object> config = new LinkedHashMap<>();
        node.put("config", config);
        return config;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getConfig(Map<String, Object> node) {
        Object raw = node.get("config");
        if (raw instanceof Map<?, ?> rawMap) {
            Map<String, Object> config = new LinkedHashMap<>();
            rawMap.forEach((k, v) -> config.put(String.valueOf(k), v));
            return config;
        }
        Object data = node.get("data");
        if (data instanceof Map<?, ?> dataMap) {
            Object dataConfig = dataMap.get("config");
            if (dataConfig instanceof Map<?, ?> dcMap) {
                Map<String, Object> config = new LinkedHashMap<>();
                dcMap.forEach((k, v) -> config.put(String.valueOf(k), v));
                return config;
            }
        }
        return Map.of();
    }

    private boolean isTriggerEnabled(WorkflowTemplate template) {
        Map<String, Object> triggerNode = findTriggerNode(template);
        if (triggerNode == null) return false;
        Map<String, Object> config = getConfig(triggerNode);
        return Boolean.TRUE.equals(config.get("enabled"));
    }

    private void validateTriggerConfig(String triggerType, Map<String, Object> config) {
        if (!Boolean.TRUE.equals(config.get("enabled"))) {
            return;
        }
        if ("trigger_cron".equals(triggerType) && !StringUtils.hasText(readString(config, "cronExpression"))) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "启用定时触发前请先配置执行周期");
        }
        if ("trigger_event".equals(triggerType) && !StringUtils.hasText(readString(config, "eventType"))) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "启用事件触发前请先配置触发点");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveTriggerInputsMap(Map<String, Object> config) {
        Object raw = config.get("triggerInputs");
        if (raw instanceof Map<?, ?> rawMap) {
            Map<String, Object> result = new LinkedHashMap<>();
            rawMap.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        return null;
    }

    private String readString(Map<String, Object> data, String key) {
        if (data == null) return null;
        Object value = data.get(key);
        return value != null ? String.valueOf(value) : null;
    }

    private WorkflowTemplate requireTemplate(String templateId) {
        WorkflowTemplate template = workflowTemplateMapper.selectById(templateId);
        if (template == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "工作流模板不存在");
        }
        return template;
    }

    private WorkflowInstanceVO toSimpleVO(WorkflowInstance instance) {
        WorkflowInstanceVO vo = new WorkflowInstanceVO();
        vo.setId(instance.getId());
        vo.setName(instance.getName());
        vo.setStatus(instance.getStatus());
        vo.setStartedAt(instance.getStartedAt());
        vo.setCompletedAt(instance.getCompletedAt());
        return vo;
    }

    /**
     * 生成 cron 表达式的人类可读描述
     */
    private String describeCron(String cronExpression) {
        if (!StringUtils.hasText(cronExpression)) return null;
        String[] parts = cronExpression.trim().split("\\s+");
        if (parts.length < 5) return cronExpression;

        String[] fields = parts.length == 5 ? parts : Arrays.copyOfRange(parts, 1, 6);
        String minute = fields[0], hour = fields[1], dom = fields[2], month = fields[3], dow = fields[4];

        if ("*".equals(minute) && "*".equals(hour)) return "每分钟执行";
        if ("*".equals(hour) && !"*".equals(minute)) return "每小时第 " + minute + " 分钟执行";
        if (!"*".equals(hour) && !"*".equals(minute) && "*".equals(dom) && "*".equals(month)) {
            if ("*".equals(dow)) return "每天 " + hour + ":" + padMinute(minute) + " 执行";
            if ("1-5".equals(dow)) return "工作日 " + hour + ":" + padMinute(minute) + " 执行";
        }
        return cronExpression;
    }

    private String padMinute(String minute) {
        try {
            return String.format("%02d", Integer.parseInt(minute));
        } catch (NumberFormatException e) {
            return minute;
        }
    }
}
