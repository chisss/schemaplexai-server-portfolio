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

    /**
     * 构建节点输入数据（合并上一节点输出 + 实例变量摘要）
     */
    public Map<String, Object> buildInputData(WorkflowInstance instance, Map<String, Object> previousOutput) {
        Map<String, Object> input = new HashMap<>();
        if (previousOutput != null) {
            input.putAll(previousOutput);
        }
        if (instance.getVariables() != null) {
            input.put("_instanceVariables", instance.getVariables());
        }
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
}
