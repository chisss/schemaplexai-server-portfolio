package com.schemaplexai.service.workflow.flowable;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.dao.mapper.WorkflowTemplateMapper;
import com.schemaplexai.model.entity.WorkflowTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 应用启动时自动将未部署的工作流模板迁移到 Flowable 引擎
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlowableTemplateMigrator {

    private final WorkflowTemplateMapper templateMapper;
    private final FlowableDeploymentService deploymentService;

    @EventListener(ApplicationReadyEvent.class)
    public void migrateTemplatesOnStartup() {
        log.info("开始检查并迁移未部署到 Flowable 的工作流模板...");

        List<WorkflowTemplate> undeployed = templateMapper.selectList(
                new LambdaQueryWrapper<WorkflowTemplate>()
                        .isNull(WorkflowTemplate::getProcessDefinitionId)
                        .isNotNull(WorkflowTemplate::getDefinition)
        );

        if (undeployed.isEmpty()) {
            log.info("所有模板已部署到 Flowable，无需迁移");
            return;
        }

        int success = 0;
        int failed = 0;
        for (WorkflowTemplate template : undeployed) {
            try {
                String processDefinitionId = deploymentService.deployTemplate(template);
                if (StringUtils.hasText(processDefinitionId)) {
                    WorkflowTemplate update = new WorkflowTemplate();
                    update.setId(template.getId());
                    update.setProcessDefinitionId(processDefinitionId);
                    update.setUpdatedAt(LocalDateTime.now());
                    templateMapper.updateById(update);
                    success++;
                    log.info("模板迁移成功: name={}, processDefinitionId={}",
                            template.getName(), processDefinitionId);
                } else {
                    failed++;
                    log.warn("模板迁移跳过（定义为空）: name={}", template.getName());
                }
            } catch (Exception e) {
                failed++;
                log.error("模板迁移失败: name={}, error={}", template.getName(), e.getMessage(), e);
            }
        }

        log.info("Flowable 模板迁移完成: 总计={}, 成功={}, 失败={}",
                undeployed.size(), success, failed);
    }
}
