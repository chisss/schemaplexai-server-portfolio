package com.schemaplexai.service.workflow.engine.assembler;

import com.schemaplexai.model.entity.WorkflowInstance;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流节点上下文组装器
 *
 * <p>负责构建节点输入数据和供 Agent 使用的上下文字符串，将组装逻辑从引擎中分离。
 */
@Component
public class WorkflowNodeContextAssembler {

    private static final String INSTRUCTION_SOURCE_UPSTREAM = "upstream";
    private static final String INSTRUCTION_SOURCE_MANUAL = "manual";
    private static final int MAX_INPUT_VALUE_LENGTH = 800;
    private static final int MAX_SECTION_LENGTH = 800;
    private static final List<String> PREFERRED_UPSTREAM_INSTRUCTION_KEYS = List.of(
            "nextTaskInstruction",
            "taskInstruction",
            "instruction",
            "prompt",
            "result",
            "agentResult",
            "summary"
    );

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
        input.put("_specId", instance.getSpecId());
        input.put("_instanceId", instance.getId());
        return input;
    }

    /**
     * 构建供 Agent 使用的上下文字符串（防止上下文爆炸：只传关键摘要）
     */
    public String buildAgentContextStr(WorkflowInstance instance, Map<String, Object> config, Map<String, Object> inputData) {
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
        appendResolvedArtifactOutputPath(sb, instance, config);
        appendConfirmedFacts(sb, config);
        appendOutputContract(sb, config);
        appendVariable(sb, instance, "workflowGoal", "流程目标");
        appendSection(sb, "原始需求", getVariable(instance, "originalRequirement"));
        appendSection(sb, "Spec描述", getVariable(instance, "specDescription"));
        appendSection(sb, "需求文档", getVariable(instance, "requirementsDoc"));
        appendSection(sb, "设计文档", getVariable(instance, "designDoc"));
        appendSection(sb, "任务文档", getVariable(instance, "tasksDoc"));
        appendPathUsageHint(sb, instance);
        appendEvidenceOutputHint(sb, config);

        if (inputData != null && !inputData.isEmpty()) {
            sb.append("上游节点输出摘要:\n");
            int summarizedCount = 0;
            for (Map.Entry<String, Object> entry : inputData.entrySet()) {
                if (entry.getKey() == null || entry.getKey().startsWith("_")) {
                    continue;
                }
                String summary = summarizePromptValue(entry.getValue());
                if (!StringUtils.hasText(summary) || isPlaceholderInstruction(summary)) {
                    continue;
                }
                sb.append("  ").append(entry.getKey()).append(": ")
                        .append(summary)
                        .append("\n");
                summarizedCount++;
                if (summarizedCount >= 5) {
                    break;
                }
            }
        }
        return sb.toString();
    }

    /**
     * 构建 Agent 执行提示词。
     *
     * <p>默认优先承接上游节点输出；仅在显式 manual 或兼容旧数据时才使用手工指令。
     */
    public String buildAgentExecutionPrompt(WorkflowInstance instance, String nodeLabel,
                                            Map<String, Object> config, Map<String, Object> inputData) {
        String instruction = resolveAgentInstruction(instance, nodeLabel, config, inputData);
        String contextStr = buildAgentContextStr(instance, config, inputData);
        if (!StringUtils.hasText(contextStr)) {
            return instruction;
        }
        return instruction + "\n\n## 当前流程上下文\n" + contextStr;
    }

    /**
     * 解析 Agent 节点任务指令来源。
     */
    public String resolveAgentInstruction(WorkflowInstance instance, String nodeLabel,
                                          Map<String, Object> config, Map<String, Object> inputData) {
        String instructionSource = resolveInstructionSource(config);
        String manualInstruction = readString(config, "taskInstruction");
        String reviewInstruction = extractReviewInstruction(inputData);
        String upstreamInstruction = extractUpstreamInstruction(inputData);
        if (INSTRUCTION_SOURCE_MANUAL.equals(instructionSource)) {
            if (StringUtils.hasText(manualInstruction)) {
                return composeManualInstruction(manualInstruction.trim(), reviewInstruction, upstreamInstruction);
            }
        }

        if (StringUtils.hasText(reviewInstruction)) {
            return buildModifyInstruction(nodeLabel, reviewInstruction, upstreamInstruction);
        }
        if (StringUtils.hasText(upstreamInstruction)) {
            return upstreamInstruction;
        }

        String workflowGoal = getVariable(instance, "workflowGoal");
        if (StringUtils.hasText(workflowGoal)) {
            return "请围绕以下流程目标完成当前节点[" + nodeLabel + "]：\n" + workflowGoal;
        }
        return "请执行当前节点[" + nodeLabel + "]，并基于上游输出与流程上下文完成本节点目标。";
    }

    private String composeManualInstruction(String manualInstruction, String reviewInstruction, String upstreamInstruction) {
        StringBuilder sb = new StringBuilder(manualInstruction);
        if (StringUtils.hasText(reviewInstruction)) {
            sb.append("\n\n## 本轮修改要求\n")
                    .append(reviewInstruction);
        }
        if (StringUtils.hasText(upstreamInstruction) && !upstreamInstruction.equals(reviewInstruction)) {
            sb.append("\n\n## 参考上游产物\n")
                    .append(upstreamInstruction);
        }
        return sb.toString();
    }

    private String buildModifyInstruction(String nodeLabel, String reviewInstruction, String upstreamInstruction) {
        StringBuilder sb = new StringBuilder("请根据以下修改意见重新执行当前节点[")
                .append(nodeLabel)
                .append("]：\n")
                .append(reviewInstruction);
        if (StringUtils.hasText(upstreamInstruction) && !upstreamInstruction.equals(reviewInstruction)) {
            sb.append("\n\n## 参考上游产物\n")
                    .append(upstreamInstruction);
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

    private void appendConfirmedFacts(StringBuilder sb, Map<String, Object> config) {
        Map<String, String> facts = new LinkedHashMap<>();
        appendFact(facts, "指令来源", resolveInstructionSource(config));
        appendFact(facts, "目标文档类型", readString(config, "artifactDocType"));
        appendFact(facts, "目标产物标题", readString(config, "artifactTitle"));
        appendFact(facts, "绑定Agent名称", readString(config, "boundAgentName"));
        appendFact(facts, "绑定Agent类型", readString(config, "boundAgentType"));
        appendFact(facts, "绑定Agent状态", readString(config, "boundAgentStatus"));
        appendFact(facts, "绑定Agent主模型", readString(config, "boundAgentModel"));
        appendFact(facts, "绑定运行时引擎", readString(config, "boundRuntimeEngine"));
        appendFact(facts, "节点最大轮次", readString(config, "maxRounds"));
        appendFact(facts, "每轮最大工具调用数", readString(config, "maxToolCallsPerRound"));
        if (facts.isEmpty()) {
            return;
        }
        sb.append("系统已确认事实:\n");
        facts.forEach((label, value) -> sb.append("- ")
                .append(label)
                .append(": ")
                .append(truncate(value, MAX_INPUT_VALUE_LENGTH))
                .append("\n"));
        sb.append("- 对以上系统已确认事实，禁止继续写成“待确认”“可能”“推测”；只有系统未提供且未读取到证据的细节，才能标记为“仓库中未发现”或“当前步骤未生成”。\n");
    }

    private void appendOutputContract(StringBuilder sb, Map<String, Object> config) {
        sb.append("输出约束:\n")
                .append("- 最终 Markdown 至少包含“已确认事实”“结论”“关键证据”“风险与未知项”四类信息。\n")
                .append("- 已有系统确认值时，直接复用该值，不要再次写“待确认”。\n")
                .append("- 系统会在文档落库前回填模型名、执行ID、质量分、文档版本等运行时元数据；不要自行猜测或编造这些字段。\n");
        String artifactDocType = readString(config, "artifactDocType");
        if ("requirements".equals(artifactDocType)) {
            sb.append("- 需求或测试结论文档优先使用表格、短列表和显式证据字段，避免空泛表述。\n");
        }
    }

    private void appendPathUsageHint(StringBuilder sb, WorkflowInstance instance) {
        sb.append("工具使用提示:\n")
                .append("- 当前 SchemaPlexAI 平台仓库可直接通过相对路径访问 docs/、schemaplexai-server/、schemaplexai-web/，不要臆测技术栈，先读文档或源码再结论。\n");
        if (StringUtils.hasText(getVariable(instance, "workspacePath"))) {
            sb.append("- 若需要读取导入工作区代码，请把 workdir 设置为“工作目录”对应的绝对路径，并确保 path 始终使用相对路径，不要把绝对路径写进 path。\n");
            sb.append("- 如果 sys.glob 连续返回空结果，不要反复重试；应直接切换为 sys.ls 定位目标目录，再用 sys.read 或 sys.grep 读取具体源码文件内容。\n");
            sb.append("- 不要从项目根目录逐级盲扫到预算耗尽；确认语言或模块根目录后，应直接下钻到 controller、api、application、domain、repository、service、event、command、sql 等目标目录。\n");
        }
    }

    private void appendEvidenceOutputHint(StringBuilder sb, Map<String, Object> config) {
        sb.append("结果约束:\n")
                .append("- 最终文档必须区分“当前仓库已确认现状”与“建议改造/待实现项”，不要把现状和方案混写。\n")
                .append("- 只有在通过 sys.read 或 sys.grep 读取到文件内容后，才能描述接口方法、注解、字段、类职责或 SQL 细节。\n")
                .append("- 仅通过 sys.ls、sys.glob 看到目录或文件名时，只能说明“存在该目录/文件”，不能推断方法或实现细节。\n")
                .append("- 如果需要列出文件路径，请直接复用工具结果中的相对路径；未验证到的路径请明确写“仓库中未发现”。\n")
                .append("- 不要把 call_function_*、工具请求编号、绝对路径或 .../xxx.java 这类占位路径写进最终文档。\n")
                .append("- 一旦已经读取到某个模块下的真实文件，就不能再把该模块整体写成“待确认”；只能把未读取到的具体细节标记为“仓库中未发现”或“待新增”。\n");
        String artifactDocType = readString(config, "artifactDocType");
        if ("requirements".equals(artifactDocType)) {
            sb.append("- 需求分析如需说明前端入口、后端接口或人工审批影响面，必须先读取对应 controller/api 文件内容，再写接口方法与行为。\n");
            sb.append("建议首批采证动作:\n")
                    .append("- 先在 *-web/src/main/java 和 *-api/src/main/java 下执行 sys.grep，模式优先使用 @RestController|@RequestMapping|@PostMapping|@GetMapping|@PutMapping|@DeleteMapping。\n")
                    .append("- 命中 controller/api 文件后，至少对 2~3 个命中文件执行 sys.read，确认类名、基础路由、方法签名和注解。\n")
                    .append("- 再读取 *-application/src/main/java 下的 command/query 服务，以及 *-domain/src/main/java 下的 repository/service/aggregate/event/command。\n")
                    .append("- 最后读取 liquibase/*.sql、**/liquibase/*.xml 或 bootstrap 模块 resources 下的 changelog，确认表结构和脚本落点。\n")
                    .append("- 如果工具结果已经出现真实 controller/api/application/liquibase 文件路径，最终文档必须列出这些相对路径，不能继续写“未读取到具体 Controller 类文件”。\n");
        }
        if ("design".equals(artifactDocType) || "tasks".equals(artifactDocType)) {
            sb.append("- 方案设计和任务拆分中涉及的模块落点、目标文件、改造范围都必须基于已确认路径，不要凭经验补全目录结构。\n");
        }
    }

    private void appendResolvedArtifactOutputPath(StringBuilder sb, WorkflowInstance instance, Map<String, Object> config) {
        String artifactOutputPath = resolveArtifactOutputPath(instance, config);
        if (StringUtils.hasText(artifactOutputPath)) {
            sb.append("目标产物: ").append(artifactOutputPath).append("\n");
        }
    }

    private void appendCompactVariable(Map<String, Object> input, WorkflowInstance instance, String key) {
        String value = getVariable(instance, key);
        if (StringUtils.hasText(value)) {
            input.put(key, truncate(value, MAX_INPUT_VALUE_LENGTH));
        }
    }

    private Object sanitizeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> compacted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                String key = String.valueOf(entry.getKey());
                if (key.startsWith("_")) {
                    continue;
                }
                if (entry.getValue() instanceof Map<?, ?> nestedMap) {
                    String nestedSummary = extractSummaryFromMap(nestedMap);
                    if (StringUtils.hasText(nestedSummary)) {
                        compacted.put(key, truncate(nestedSummary, MAX_INPUT_VALUE_LENGTH));
                    }
                } else if (entry.getValue() instanceof List<?> listValue) {
                    compacted.put(key, truncate(String.valueOf(listValue.stream().limit(5).toList()), MAX_INPUT_VALUE_LENGTH));
                } else {
                    compacted.put(key, truncate(String.valueOf(entry.getValue()), MAX_INPUT_VALUE_LENGTH));
                }
                if (compacted.size() >= 8) {
                    break;
                }
            }
            return compacted;
        }
        if (value instanceof List<?> listValue) {
            return truncate(String.valueOf(listValue.stream().limit(5).toList()), MAX_INPUT_VALUE_LENGTH);
        }
        return truncate(String.valueOf(value), MAX_INPUT_VALUE_LENGTH);
    }

    private String getVariable(WorkflowInstance instance, String key) {
        if (instance.getVariables() == null) {
            return null;
        }
        Object value = instance.getVariables().get(key);
        if (value instanceof Map<?, ?> mapValue) {
            return extractSummaryFromMap(mapValue);
        }
        return value == null ? null : String.valueOf(value);
    }

    private String resolveArtifactOutputPath(WorkflowInstance instance, Map<String, Object> config) {
        String configuredOutputPath = readString(config, "artifactOutputPath");
        if (StringUtils.hasText(configuredOutputPath)) {
            return configuredOutputPath.trim();
        }

        String artifactDocType = readString(config, "artifactDocType");
        if (!StringUtils.hasText(artifactDocType)) {
            return getVariable(instance, "artifactOutputPath");
        }

        String fileKey = resolveArtifactFileKey(instance);
        return switch (artifactDocType.trim()) {
            case "requirements" -> "docs/" + fileKey + "-requirements.md";
            case "design" -> "docs/" + fileKey + "-technical-design.md";
            case "tasks" -> "docs/" + fileKey + "-task-breakdown.md";
            default -> getVariable(instance, "artifactOutputPath");
        };
    }

    private String resolveArtifactFileKey(WorkflowInstance instance) {
        String jiraTicket = getVariable(instance, "jiraTicket");
        if (StringUtils.hasText(jiraTicket)) {
            return jiraTicket.trim();
        }
        String specName = getVariable(instance, "specName");
        if (StringUtils.hasText(specName)) {
            String sanitized = specName.trim().toLowerCase()
                    .replaceAll("[^a-z0-9._-]+", "-")
                    .replaceAll("-{2,}", "-")
                    .replaceAll("^-|-$", "");
            if (StringUtils.hasText(sanitized)) {
                return sanitized;
            }
        }
        return "spec";
    }

    private String resolveInstructionSource(Map<String, Object> config) {
        String instructionSource = readString(config, "instructionSource");
        if (StringUtils.hasText(instructionSource)) {
            return instructionSource.trim();
        }
        String manualInstruction = readString(config, "taskInstruction");
        return StringUtils.hasText(manualInstruction) ? INSTRUCTION_SOURCE_MANUAL : INSTRUCTION_SOURCE_UPSTREAM;
    }

    private String extractReviewInstruction(Map<String, Object> inputData) {
        if (inputData == null || inputData.isEmpty()) {
            return null;
        }
        return normalizeInstruction(inputData.get("modifyInstruction"));
    }

    private String extractUpstreamInstruction(Map<String, Object> inputData) {
        if (inputData == null || inputData.isEmpty()) {
            return null;
        }
        for (String key : PREFERRED_UPSTREAM_INSTRUCTION_KEYS) {
            String instruction = normalizeInstruction(inputData.get(key));
            if (StringUtils.hasText(instruction) && !isPlaceholderInstruction(instruction)) {
                return instruction;
            }
        }
        return inputData.entrySet().stream()
                .filter(entry -> !isIgnoredInstructionEntry(entry.getKey(), entry.getValue()))
                .map(entry -> normalizeInstruction(entry.getValue()))
                .filter(StringUtils::hasText)
                .filter(instruction -> !isPlaceholderInstruction(instruction))
                .findFirst()
                .orElse(null);
    }

    private boolean isIgnoredInstructionEntry(String key, Object value) {
        if (!StringUtils.hasText(key) || value == null) {
            return true;
        }
        if (key.startsWith("_")) {
            return true;
        }
        return List.of("agentStatus", "completedAt", "triggered", "triggeredAt", "approvalResult", "comment")
                .contains(key);
    }

    private String extractSummaryFromMap(Map<?, ?> mapValue) {
        if (mapValue == null || mapValue.isEmpty()) {
            return null;
        }
        for (String preferredKey : List.of("summary", "nextTaskInstruction", "qualityGateMessage", "title", "outputPath")) {
            Object value = mapValue.get(preferredKey);
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return String.valueOf(value);
            }
        }
        if (mapValue.containsKey("docRef") && mapValue.get("docRef") instanceof Map<?, ?> docRef) {
            return extractSummaryFromMap(docRef);
        }
        if (mapValue.containsKey("tracePayload") && mapValue.get("tracePayload") instanceof Map<?, ?> tracePayload) {
            return extractSummaryFromMap(tracePayload);
        }
        if (mapValue.containsKey("handoffPayload") && mapValue.get("handoffPayload") instanceof Map<?, ?> handoffPayload) {
            return extractSummaryFromMap(handoffPayload);
        }
        return truncate(String.valueOf(mapValue), MAX_SECTION_LENGTH);
    }

    private void appendFact(Map<String, String> facts, String label, String value) {
        if (facts == null || !StringUtils.hasText(label) || !StringUtils.hasText(value)) {
            return;
        }
        facts.put(label, value.trim());
    }

    private String summarizePromptValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> mapValue) {
            String summary = extractSummaryFromMap(mapValue);
            return StringUtils.hasText(summary) ? truncate(summary, 200) : null;
        }
        if (value instanceof List<?> listValue) {
            return truncate(String.valueOf(listValue.stream().limit(3).toList()), 200);
        }
        return truncate(String.valueOf(value), 200);
    }

    private String normalizeInstruction(Object value) {
        if (value == null) {
            return null;
        }
        String text = value instanceof Map<?, ?> mapValue
                ? extractSummaryFromMap(mapValue)
                : truncate(String.valueOf(value).trim(), MAX_INPUT_VALUE_LENGTH);
        return StringUtils.hasText(text) ? text : null;
    }

    private boolean isPlaceholderInstruction(String instruction) {
        if (!StringUtils.hasText(instruction)) {
            return false;
        }
        String normalized = instruction.replaceAll("\\s+", "");
        return normalized.matches("^\\{?[a-zA-Z_]+[:=](?:true|false|null|-?\\d+)\\}?$");
    }

    private String readString(Map<String, Object> data, String key) {
        if (data == null) {
            return null;
        }
        Object value = data.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
