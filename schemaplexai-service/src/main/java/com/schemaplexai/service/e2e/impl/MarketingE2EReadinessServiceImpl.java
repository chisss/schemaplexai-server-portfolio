package com.schemaplexai.service.e2e.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.enums.SourceTypeEnum;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.dao.mapper.AgentToolBindingMapper;
import com.schemaplexai.dao.mapper.SkillMapper;
import com.schemaplexai.dao.mapper.WorkflowTemplateMapper;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.model.entity.Skill;
import com.schemaplexai.model.entity.WorkflowTemplate;
import com.schemaplexai.model.vo.e2e.MarketingReadinessVO;
import com.schemaplexai.model.vo.e2e.MarketingScenarioReadinessItemVO;
import com.schemaplexai.model.vo.e2e.ToolBindingReadinessVO;
import com.schemaplexai.service.agent.tool.validator.SkillAccessResult;
import com.schemaplexai.service.agent.tool.validator.SkillAccessValidator;
import com.schemaplexai.service.e2e.MarketingE2EReadinessService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 营销场景回归准备度服务实现
 */
@Service
@RequiredArgsConstructor
public class MarketingE2EReadinessServiceImpl implements MarketingE2EReadinessService {

    private static final Pattern MARKETING_DOC_PATTERN = Pattern.compile("^(\\d{2})-SchemaPlexAI-(.+)\\.md$");
    private static final String TEMPLATE_PREFIX = "SchemaPlexAI演示-";

    private final WorkflowTemplateMapper workflowTemplateMapper;
    private final AgentMapper agentMapper;
    private final AgentToolBindingMapper agentToolBindingMapper;
    private final SkillMapper skillMapper;
    private final SkillAccessValidator skillAccessValidator;

    @Value("${schemaplexai.marketing-doc-path:document/marketing}")
    private String marketingDocPath;

    @Override
    public MarketingReadinessVO checkMarketingScenarios(int from, int to) {
        int normalizedFrom = Math.max(1, from);
        int normalizedTo = Math.max(normalizedFrom, to);
        List<MarketingScenarioReadinessItemVO> items = new ArrayList<>();
        for (int code = normalizedFrom; code <= normalizedTo; code++) {
            items.add(checkScenario(String.format("%02d", code)));
        }
        MarketingReadinessVO result = new MarketingReadinessVO();
        result.setScenarioCount(items.size());
        result.setBlockingCount((int) items.stream().filter(item -> Boolean.TRUE.equals(item.getBlocking())).count());
        result.setReadyCount(items.size() - result.getBlockingCount());
        result.setItems(items);
        return result;
    }

    private MarketingScenarioReadinessItemVO checkScenario(String scenarioCode) {
        MarketingScenarioReadinessItemVO item = new MarketingScenarioReadinessItemVO();
        item.setScenarioCode(scenarioCode);
        Path documentPath = resolveDocumentPath(scenarioCode);
        item.setDocumentPath(documentPath != null ? documentPath.toString() : marketingDocPath + "/" + scenarioCode + "-*.md");
        item.setScenarioName(extractScenarioName(documentPath, scenarioCode));
        item.setWorkflowTemplateName(TEMPLATE_PREFIX + item.getScenarioName());
        if (documentPath == null) {
            markBlocking(item, "MISSING_DOCUMENT", "NOT_CHECKED", "NOT_CHECKED", "请补充场景文档");
            return item;
        }
        WorkflowTemplate template = findTemplate(item.getWorkflowTemplateName());
        if (template == null) {
            markBlocking(item, "MISSING_WORKFLOW_TEMPLATE", "NOT_CHECKED", "NOT_CHECKED",
                    "创建或绑定 " + scenarioCode + " " + item.getScenarioName() + " 工作流模板");
            return item;
        }
        item.setWorkflowTemplateStatus(StringUtils.hasText(template.getStatus()) ? template.getStatus() : "UNKNOWN");
        Agent agent = findScenarioAgent(item.getScenarioName());
        if (agent == null) {
            markBlocking(item, item.getWorkflowTemplateStatus(), "MISSING_AGENT", "NOT_CHECKED",
                    "请为场景绑定或创建演示 Agent");
            return item;
        }
        item.setAgentStatus(StringUtils.hasText(agent.getStatus()) ? agent.getStatus() : "UNKNOWN");
        List<ToolBindingReadinessVO> toolBindings = checkToolBindings(agent);
        item.setToolBindings(toolBindings);
        boolean hasBlockingTool = toolBindings.stream().anyMatch(binding -> "BLOCKING".equals(binding.getStatus()));
        if (hasBlockingTool) {
            markBlocking(item, item.getWorkflowTemplateStatus(), item.getAgentStatus(), "BLOCKING",
                    "请修复不可用工具或 Skill 绑定");
            return item;
        }
        item.setToolBindingStatus(CollectionUtils.isEmpty(toolBindings) ? "NO_ENABLED_TOOLS" : "READY");
        item.setBlocking(false);
        item.setSuggestion("READY");
        return item;
    }

    private List<ToolBindingReadinessVO> checkToolBindings(Agent agent) {
        return agentToolBindingMapper.selectList(new LambdaQueryWrapper<AgentToolBinding>()
                        .eq(AgentToolBinding::getAgentId, agent.getId())
                        .eq(AgentToolBinding::getEnabled, true))
                .stream()
                .map(binding -> checkToolBinding(agent.getTenantId(), binding))
                .toList();
    }

    private ToolBindingReadinessVO checkToolBinding(String tenantId, AgentToolBinding binding) {
        ToolBindingReadinessVO result = new ToolBindingReadinessVO();
        result.setBindingId(binding.getId());
        result.setToolCode(binding.getToolCode());
        result.setSourceType(binding.getSourceType());
        result.setSourceRefId(binding.getSourceRefId());
        String sourceType = StringUtils.hasText(binding.getSourceType())
                ? binding.getSourceType().trim().toLowerCase()
                : SourceTypeEnum.BUILTIN.getCode();
        if (!SourceTypeEnum.SKILL.getCode().equals(sourceType)) {
            result.setStatus("READY");
            result.setMessage("工具绑定可用");
            return result;
        }
        Skill skill = StringUtils.hasText(binding.getSourceRefId())
                ? skillMapper.selectById(binding.getSourceRefId())
                : null;
        SkillAccessResult accessResult = skillAccessValidator.validate(tenantId, skill);
        result.setStatus(accessResult.accessible() ? "READY" : "BLOCKING");
        result.setMessage(accessResult.accessible() ? accessResult.status() : accessResult.message());
        return result;
    }

    private WorkflowTemplate findTemplate(String templateName) {
        return workflowTemplateMapper.selectOne(new LambdaQueryWrapper<WorkflowTemplate>()
                .eq(WorkflowTemplate::getName, templateName)
                .last("LIMIT 1"));
    }

    private Agent findScenarioAgent(String scenarioName) {
        return agentMapper.selectOne(new LambdaQueryWrapper<Agent>()
                .like(Agent::getName, TEMPLATE_PREFIX + scenarioName)
                .last("LIMIT 1"));
    }

    private Path resolveDocumentPath(String scenarioCode) {
        Path root = Path.of(marketingDocPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (var stream = Files.list(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(scenarioCode + "-"))
                    .findFirst()
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String extractScenarioName(Path documentPath, String scenarioCode) {
        if (documentPath == null) {
            return scenarioCode;
        }
        Matcher matcher = MARKETING_DOC_PATTERN.matcher(documentPath.getFileName().toString());
        if (matcher.matches()) {
            return matcher.group(2);
        }
        return scenarioCode;
    }

    private void markBlocking(MarketingScenarioReadinessItemVO item, String workflowTemplateStatus,
                              String agentStatus, String toolBindingStatus, String suggestion) {
        item.setWorkflowTemplateStatus(workflowTemplateStatus);
        item.setAgentStatus(agentStatus);
        item.setToolBindingStatus(toolBindingStatus);
        item.setBlocking(true);
        item.setSuggestion(suggestion);
    }
}
