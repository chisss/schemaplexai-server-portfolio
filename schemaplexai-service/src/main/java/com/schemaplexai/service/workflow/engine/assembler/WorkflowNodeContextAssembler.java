package com.schemaplexai.service.workflow.engine.assembler;

import com.schemaplexai.model.entity.WorkflowInstance;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 工作流节点上下文组装器
 *
 * <p>负责构建节点输入数据和供 Agent 使用的上下文字符串，将组装逻辑从引擎中分离。
 */
@Component
public class WorkflowNodeContextAssembler {

    private static final int MAX_INPUT_VALUE_LENGTH = 800;
    private static final int MAX_SECTION_LENGTH = 800;

    /**
     * 构建节点输入数据（合并上一节点输出 + 实例变量摘要）
     */
    public Map<String, Object> buildInputData(WorkflowInstance instance, Map<String, Object> previousOutput) {
        Map<String, Object> input = new HashMap<>();
        if (previousOutput != null) {
            previousOutput.forEach((key, value) -> input.put(key, sanitizeValue(value)));
        }
        appendCompactVariable(input, instance, "specName");
        appendCompactVariable(input, instance, "jiraTicket");
        appendCompactVariable(input, instance, "targetBranch");
        appendCompactVariable(input, instance, "workspaceName");
        appendCompactVariable(input, instance, "workspacePath");
        appendCompactVariable(input, instance, "artifactOutputPath");
        input.put("_specId", instance.getSpecId());
        input.put("_instanceId", instance.getId());
        return input;
    }

    /**
     * 构建供 Agent 使用的上下文字符串（防止上下文爆炸：只传关键摘要）
     */
    public String buildAgentContextStr(WorkflowInstance instance, Map<String, Object> inputData) {
        StringBuilder sb = new StringBuilder();
        sb.append("工作流实例: ").append(instance.getName()).append("\n");
        if (StringUtils.hasText(instance.getSpecId())) {
            sb.append("关联Spec ID: ").append(instance.getSpecId()).append("\n");
        }
        appendVariable(sb, instance, "specName", "Spec名称");
        appendVariable(sb, instance, "jiraTicket", "Jira号");
        appendVariable(sb, instance, "targetBranch", "目标分支");
        appendVariable(sb, instance, "workspaceName", "工作空间");
        appendVariable(sb, instance, "workspacePath", "工作目录");
        appendVariable(sb, instance, "artifactOutputPath", "目标产物");
        appendVariable(sb, instance, "workflowGoal", "流程目标");
        appendSection(sb, "Spec描述", getVariable(instance, "specDescription"));
        appendSection(sb, "需求文档", getVariable(instance, "requirementsDoc"));
        appendSection(sb, "设计文档", getVariable(instance, "designDoc"));
        appendSection(sb, "任务文档", getVariable(instance, "tasksDoc"));

        if (inputData != null && !inputData.isEmpty()) {
            sb.append("上游节点输出摘要:\n");
            inputData.entrySet().stream()
                    .filter(e -> !e.getKey().startsWith("_"))
                    .limit(5)
                    .forEach(e -> sb.append("  ").append(e.getKey()).append(": ")
                            .append(truncate(String.valueOf(e.getValue()), 200))
                            .append("\n"));
        }
        return sb.toString();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    private void appendVariable(StringBuilder sb, WorkflowInstance instance, String key, String label) {
        String value = getVariable(instance, key);
        if (StringUtils.hasText(value)) {
            sb.append(label).append(": ").append(value).append("\n");
        }
    }

    private void appendSection(StringBuilder sb, String title, String content) {
        if (StringUtils.hasText(content)) {
            sb.append(title).append(":\n")
                    .append(truncate(content, MAX_SECTION_LENGTH))
                    .append("\n");
        }
    }

    private void appendCompactVariable(Map<String, Object> input, WorkflowInstance instance, String key) {
        String value = getVariable(instance, key);
        if (StringUtils.hasText(value)) {
            input.put(key, truncate(value, MAX_INPUT_VALUE_LENGTH));
        }
    }

    private String sanitizeValue(Object value) {
        if (value == null) {
            return null;
        }
        return truncate(String.valueOf(value), MAX_INPUT_VALUE_LENGTH);
    }

    private String getVariable(WorkflowInstance instance, String key) {
        if (instance.getVariables() == null) {
            return null;
        }
        Object value = instance.getVariables().get(key);
        return value == null ? null : String.valueOf(value);
    }
}
