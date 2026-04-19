package com.schemaplexai.service.workflow.flowable;

import com.schemaplexai.model.entity.WorkflowTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Service;

/**
 * Flowable BPMN 部署服务
 *
 * <p>负责将工作流模板的 ReactFlow definition 转换为 BPMN 并部署到 Flowable 引擎。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlowableDeploymentService {

    private final RepositoryService repositoryService;
    private final ReactFlowToBpmnConverter bpmnConverter;

    /**
     * 部署工作流模板到 Flowable，返回 processDefinitionId
     */
    public String deployTemplate(WorkflowTemplate template) {
        if (template.getDefinition() == null || template.getDefinition().isEmpty()) {
            log.warn("模板定义为空，跳过部署: templateId={}", template.getId());
            return null;
        }

        BpmnModel bpmnModel = bpmnConverter.convert(
                template.getId(), template.getName(), template.getDefinition());

        String resourceName = "sf-workflow-" + template.getId() + ".bpmn20.xml";
        Deployment deployment = repositoryService.createDeployment()
                .name("sf-workflow-" + template.getName())
                .addBpmnModel(resourceName, bpmnModel)
                .tenantId(template.getTenantId() != null ? template.getTenantId() : "")
                .deploy();

        ProcessDefinition pd = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deployment.getId())
                .singleResult();

        if (pd == null) {
            log.error("部署后未找到流程定义: templateId={}, deploymentId={}",
                    template.getId(), deployment.getId());
            return null;
        }

        log.info("模板部署成功: templateId={}, processDefinitionId={}, version={}",
                template.getId(), pd.getId(), pd.getVersion());
        return pd.getId();
    }

    /**
     * 检查模板是否已部署
     */
    public boolean isDeployed(String processDefinitionId) {
        if (processDefinitionId == null) {
            return false;
        }
        try {
            ProcessDefinition pd = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionId(processDefinitionId)
                    .singleResult();
            return pd != null;
        } catch (Exception e) {
            return false;
        }
    }
}
