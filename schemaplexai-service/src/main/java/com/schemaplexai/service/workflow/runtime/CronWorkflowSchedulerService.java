package com.schemaplexai.service.workflow.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.dao.mapper.WorkflowInstanceMapper;
import com.schemaplexai.dao.mapper.WorkflowTemplateMapper;
import com.schemaplexai.model.dto.workflow.WorkflowInstanceCreateRequest;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowTemplate;
import com.schemaplexai.model.vo.workflow.WorkflowInstanceVO;
import com.schemaplexai.service.workflow.WorkflowInstanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cron 工作流调度服务
 *
 * <p>定期扫描已发布模板中的 trigger_cron 节点；当命中当前分钟时间窗时，
 * 自动创建工作流实例并把节点配置中的入参注入 variables，形成真实可运行链路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CronWorkflowSchedulerService {

    private static final String TRIGGER_TYPE_CRON = "cron";
    private static final ZoneId DEFAULT_ZONE_ID = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter INSTANCE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final WorkflowTemplateMapper workflowTemplateMapper;
    private final WorkflowInstanceMapper workflowInstanceMapper;
    private final ObjectProvider<WorkflowInstanceService> workflowInstanceServiceProvider;
    private final ObjectMapper objectMapper;

    /**
     * 轮询 cron 模板并尝试触发。
     */
    @Scheduled(
            initialDelayString = "${schemaplexai.workflow.cron.initial-delay-ms:10000}",
            fixedDelayString = "${schemaplexai.workflow.cron.poll-interval-ms:30000}"
    )
    public void scheduleCronWorkflows() {
        List<WorkflowTemplate> templates = workflowTemplateMapper.selectList(
                new LambdaQueryWrapper<WorkflowTemplate>()
                        .eq(WorkflowTemplate::getStatus, "published")
                        .orderByAsc(WorkflowTemplate::getUpdatedAt)
        );
        for (WorkflowTemplate template : templates) {
            try {
                triggerTemplateIfDue(template);
            } catch (Exception exception) {
                log.error("Cron 模板调度失败: templateId={}, name={}, error={}",
                        template.getId(), template.getName(), exception.getMessage(), exception);
            }
        }
    }

    void triggerTemplateIfDue(WorkflowTemplate template) {
        Map<String, Object> triggerNode = findCronTriggerNode(template);
        if (triggerNode == null) {
            return;
        }

        Map<String, Object> config = getConfig(triggerNode);
        String cronExpressionText = readString(config, "cronExpression");
        if (!StringUtils.hasText(cronExpressionText)) {
            log.warn("Cron 模板缺少 cronExpression，跳过触发: templateId={}", template.getId());
            return;
        }
        if (!StringUtils.hasText(template.getTenantId())) {
            log.debug("Cron 模板缺少 tenantId，暂不自动触发: templateId={}", template.getId());
            return;
        }

        ZoneId zoneId = resolveZoneId(readString(config, "timezone"));
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        ZonedDateTime fireTime = resolveDueFireTime(cronExpressionText, now);
        if (fireTime == null) {
            return;
        }

        String fireKey = fireTime.withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        if (alreadyTriggered(template.getId(), fireKey)) {
            return;
        }

        Map<String, Object> variables = buildTriggerVariables(triggerNode, config, cronExpressionText, zoneId, fireKey);
        WorkflowInstanceCreateRequest request = new WorkflowInstanceCreateRequest();
        request.setTemplateId(template.getId());
        request.setTenantId(template.getTenantId());
        request.setName(template.getName() + " - " + INSTANCE_TIME_FORMATTER.format(fireTime));
        request.setVariables(variables);

        WorkflowInstanceService workflowInstanceService = workflowInstanceServiceProvider.getObject();
        WorkflowInstanceVO created = workflowInstanceService.create(request);
        workflowInstanceService.start(created.getId());

        log.info("Cron 模板触发成功: templateId={}, instanceId={}, fireAt={}",
                template.getId(), created.getId(), fireKey);
    }

    ZonedDateTime resolveDueFireTime(String cronExpressionText, ZonedDateTime currentTime) {
        String normalizedCron = normalizeCronExpression(cronExpressionText);
        CronExpression cronExpression = CronExpression.parse(normalizedCron);
        ZonedDateTime slotStart = currentTime.truncatedTo(ChronoUnit.MINUTES);
        ZonedDateTime slotEnd = slotStart.plusMinutes(1);
        ZonedDateTime nextFireTime = cronExpression.next(slotStart.minusSeconds(1));
        if (nextFireTime == null) {
            return null;
        }
        if (nextFireTime.isBefore(slotStart) || !nextFireTime.isBefore(slotEnd)) {
            return null;
        }
        return nextFireTime;
    }

    Map<String, Object> buildTriggerVariables(Map<String, Object> triggerNode, Map<String, Object> config,
                                              String cronExpressionText, ZoneId zoneId, String fireKey) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("triggerType", TRIGGER_TYPE_CRON);
        variables.put("cronExpression", cronExpressionText);
        variables.put("cronTimezone", zoneId.getId());
        variables.put("cronFireAt", fireKey);
        variables.put("triggerNodeId", readString(triggerNode, "id"));
        variables.put("triggerNodeLabel", readString(triggerNode, "label"));
        variables.putAll(resolveTriggerInputs(config));
        return variables;
    }

    String normalizeCronExpression(String cronExpressionText) {
        String trimmed = cronExpressionText == null ? "" : cronExpressionText.trim();
        String[] segments = trimmed.split("\\s+");
        if (segments.length == 5) {
            return "0 " + trimmed;
        }
        return trimmed;
    }

    Map<String, Object> resolveTriggerInputs(Map<String, Object> config) {
        Object raw = config.get("triggerInputs");
        if (raw == null) {
            return Map.of();
        }
        if (raw instanceof Map<?, ?> rawMap) {
            Map<String, Object> parsed = new LinkedHashMap<>();
            rawMap.forEach((key, value) -> parsed.put(String.valueOf(key), value));
            return parsed;
        }
        if (raw instanceof String text && StringUtils.hasText(text)) {
            try {
                return objectMapper.readValue(text, new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception exception) {
                throw new IllegalStateException("Cron 入参 JSON 解析失败: " + exception.getMessage(), exception);
            }
        }
        throw new IllegalStateException("Cron 入参仅支持 JSON 对象");
    }

    private boolean alreadyTriggered(String templateId, String fireKey) {
        List<WorkflowInstance> instances = workflowInstanceMapper.selectList(
                new LambdaQueryWrapper<WorkflowInstance>()
                        .eq(WorkflowInstance::getTemplateId, templateId)
                        .ge(WorkflowInstance::getCreatedAt, LocalDateTime.now().minusMinutes(2))
                        .orderByDesc(WorkflowInstance::getCreatedAt)
        );
        return instances.stream()
                .map(WorkflowInstance::getVariables)
                .filter(variables -> variables != null)
                .map(variables -> readString(variables, "cronFireAt"))
                .anyMatch(fireKey::equals);
    }

    private Map<String, Object> findCronTriggerNode(WorkflowTemplate template) {
        if (template == null || template.getDefinition() == null) {
            return null;
        }
        Object rawNodes = template.getDefinition().get("nodes");
        if (!(rawNodes instanceof List<?> nodes)) {
            return null;
        }
        for (Object rawNode : nodes) {
            if (rawNode instanceof Map<?, ?> rawMap) {
                Map<String, Object> node = new LinkedHashMap<>();
                rawMap.forEach((key, value) -> node.put(String.valueOf(key), value));
                if ("trigger_cron".equals(readString(node, "type"))) {
                    return node;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getConfig(Map<String, Object> node) {
        Object rawConfig = node.get("config");
        if (rawConfig instanceof Map<?, ?> rawMap) {
            Map<String, Object> config = new LinkedHashMap<>();
            rawMap.forEach((key, value) -> config.put(String.valueOf(key), value));
            return config;
        }
        return Map.of();
    }

    private ZoneId resolveZoneId(String timezone) {
        if (!StringUtils.hasText(timezone)) {
            return DEFAULT_ZONE_ID;
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (Exception exception) {
            log.warn("非法时区配置，回退默认时区: timezone={}", timezone);
            return DEFAULT_ZONE_ID;
        }
    }

    private String readString(Map<String, Object> data, String key) {
        if (data == null || !StringUtils.hasText(key)) {
            return null;
        }
        Object value = data.get(key);
        return value != null ? String.valueOf(value) : null;
    }
}
