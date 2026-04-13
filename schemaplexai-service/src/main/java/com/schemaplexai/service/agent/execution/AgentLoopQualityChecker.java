package com.schemaplexai.service.agent.execution;

import com.schemaplexai.common.constant.AgentPatternConstant;
import com.schemaplexai.service.agent.execution.AgentLoopCompletionHandler.QualityReflectionFeedback;
import com.schemaplexai.service.quality.detector.QualityDetector;
import com.schemaplexai.service.quality.orchestrator.QualityOrchestrator;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.ChatMemory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agentic Loop 质量检测器
 *
 * <p>负责以下质量检测：
 * <ol>
 *   <li>Artifact 格式检测：输出是否包含内部工具痕迹、占位路径、绝对路径</li>
 *   <li>工作区 Grounding 检测：输出引用的文件路径是否在工作区中真实存在</li>
 *   <li>证据覆盖检测：工具证据命中的路径是否在输出中被引用</li>
 * </ol>
 * </p>
 */
@Component
@RequiredArgsConstructor
public class AgentLoopQualityChecker {

    private final QualityOrchestrator qualityOrchestrator;

    // =========================================================================
    //  对外接口
    // =========================================================================

    /**
     * 构建质量反思反馈，若无问题则返回 null
     */
    public QualityReflectionFeedback buildFeedback(AgentExecutionContext ctx,
                                                    String content,
                                                    ChatMemory chatMemory,
                                                    int qualityReflectionCount,
                                                    AgentEngineParams params) {
        if (!canCheck(content, qualityReflectionCount, params)) {
            return null;
        }
        QualityReflectionFeedback structural = buildStructuralFeedback(ctx, content, qualityReflectionCount, params);
        if (structural != null) {
            return structural;
        }
        return buildImmediateFeedback(ctx, content, chatMemory, qualityReflectionCount, params);
    }

    /**
     * 构建同步轻闸门反馈，只做本地、低延迟、确定性规则
     */
    public QualityReflectionFeedback buildImmediateFeedback(AgentExecutionContext ctx,
                                                             String content,
                                                             ChatMemory chatMemory,
                                                             int qualityReflectionCount,
                                                             AgentEngineParams params) {
        if (!canCheck(content, qualityReflectionCount, params)) {
            return null;
        }
        QualityDetector.DetectionResult formatting = detectArtifactFormatting(content);
        if (formatting != null && formatting.hasIssue()) {
            return toFeedback(formatting);
        }

        QualityDetector.DetectionResult deliverable = detectInsufficientDeliverable(content);
        if (deliverable != null && deliverable.hasIssue()) {
            return toFeedback(deliverable);
        }

        QualityDetector.DetectionResult grounding = detectWorkspaceGrounding(ctx, content, chatMemory);
        if (grounding != null && grounding.hasIssue()) {
            return toFeedback(grounding);
        }
        return null;
    }

    /**
     * 构建结构性影子审核反馈，适合异步执行
     */
    public QualityReflectionFeedback buildStructuralFeedback(AgentExecutionContext ctx,
                                                              String content,
                                                              int qualityReflectionCount,
                                                              AgentEngineParams params) {
        if (!canCheck(content, qualityReflectionCount, params)) {
            return null;
        }
        QualityDetector.DetectionResult structural = detectStructuralIssue(ctx, content);
        return structural != null && structural.hasIssue() ? toFeedback(structural) : null;
    }

    /**
     * 清洗输出内容中的内部工具痕迹、占位路径、绝对路径
     */
    public String sanitize(String content) {
        if (!StringUtils.hasText(content)) return content;
        String s = AgentPatternConstant.XML_TOOL_CALL_BLOCK.matcher(content).replaceAll("工具执行记录");
        s = AgentPatternConstant.XML_TOOL_INVOKE_BLOCK.matcher(s).replaceAll("工具执行记录");
        s = AgentPatternConstant.XML_TOOL_TAG.matcher(s).replaceAll("");
        s = AgentPatternConstant.INTERNAL_TOOL_TRACE.matcher(s).replaceAll("工具执行记录");
        s = AgentPatternConstant.INTERNAL_TOOL_NAME.matcher(s).replaceAll("仓库检索工具");
        s = AgentPatternConstant.PLACEHOLDER_PATH.matcher(s).replaceAll("仓库中未发现具体相对路径");
        s = AgentPatternConstant.ABSOLUTE_FILE_PATH.matcher(s).replaceAll("仓库中未发现具体相对路径");
        s = AgentPatternConstant.ABSOLUTE_WORKSPACE_PATH.matcher(s).replaceAll("仓库工作区路径");
        s = s.replaceAll("(?m)^\\s*工具执行记录\\s*$", "");
        s = s.replaceAll("(?m)^\\s*<[^>]+>\\s*$", "");
        s = s.replaceAll("\\n{3,}", "\n\n").trim();
        return s;
    }

    // =========================================================================
    //  私有检测方法
    // =========================================================================

    private QualityDetector.DetectionResult detectArtifactFormatting(String content) {
        Map<String, List<String>> issues = new LinkedHashMap<>();
        checkPattern(issues, content, "internalToolTraces",  AgentPatternConstant.INTERNAL_TOOL_TRACE, 3);
        checkPattern(issues, content, "internalToolNames",   AgentPatternConstant.INTERNAL_TOOL_NAME, 3);
        checkPattern(issues, content, "xmlToolCalls",        AgentPatternConstant.XML_TOOL_CALL_BLOCK, 2);
        checkPattern(issues, content, "xmlInvokeBlocks",     AgentPatternConstant.XML_TOOL_INVOKE_BLOCK, 2);
        checkPattern(issues, content, "placeholderPaths",    AgentPatternConstant.PLACEHOLDER_PATH, 3);
        checkPattern(issues, content, "absoluteFilePaths",   AgentPatternConstant.ABSOLUTE_FILE_PATH, 3);
        checkPattern(issues, content, "absoluteWorkspacePaths", AgentPatternConstant.ABSOLUTE_WORKSPACE_PATH, 3);
        if (issues.isEmpty()) return null;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("detector", "artifact_formatting");
        details.putAll(issues);
        return new QualityDetector.DetectionResult(true, "warning",
                "输出包含内部工具痕迹、占位路径或绝对路径，不能直接作为最终审批文档", details);
    }

    private QualityDetector.DetectionResult detectInsufficientDeliverable(String content) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        List<String> matchedSignals = collectMatches(content, AgentPatternConstant.INSUFFICIENT_DELIVERABLE, 4);
        if (matchedSignals.isEmpty()) {
            return null;
        }
        boolean looksLikeBlockedAnswer = matchedSignals.size() >= 2 || content.length() <= 800;
        if (!looksLikeBlockedAnswer) {
            return null;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("detector", "deliverable_completeness");
        details.put("matchedSignals", matchedSignals);
        details.put("contentLength", content.length());
        return new QualityDetector.DetectionResult(true, "warning",
                "输出仍停留在受阻或待补充输入阶段，未形成可交付结果", details);
    }

    private QualityDetector.DetectionResult detectStructuralIssue(AgentExecutionContext ctx, String content) {
        Map<String, Object> structuralRuleConfig = ctx != null && StringUtils.hasText(ctx.getModel())
                ? Map.of("modelId", ctx.getModel())
                : Map.of();
        return qualityOrchestrator != null
                ? (structuralRuleConfig.isEmpty()
                ? qualityOrchestrator.executeDetection(null, "structural", content)
                : qualityOrchestrator.executeDetection(null, "structural", content, structuralRuleConfig))
                : null;
    }

    private QualityDetector.DetectionResult detectWorkspaceGrounding(AgentExecutionContext ctx,
                                                                       String content,
                                                                       ChatMemory chatMemory) {
        if (ctx == null || !StringUtils.hasText(content)) return null;
        Path workspaceRoot = resolveWorkspaceRoot(ctx);
        if (workspaceRoot == null) return null;

        Set<String> referenced = extractFileReferences(content);
        List<String> invalid = new ArrayList<>();
        for (String path : referenced) {
            if (!isExistingFile(workspaceRoot, path)) {
                invalid.add(path);
                if (invalid.size() >= 5) break;
            }
        }
        if (!invalid.isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("detector", "workspace_grounding");
            details.put("invalidPaths", invalid);
            return new QualityDetector.DetectionResult(true, "warning",
                    "输出包含仓库中不存在的文件路径，请严格基于真实工具证据修订", details);
        }

        if (!shouldCheckEvidenceCoverage(ctx, content)) return null;
        Set<String> toolPaths = extractToolReferencedPaths(chatMemory);
        if (toolPaths.isEmpty()) return null;

        Map<String, List<String>> missing = collectMissingEvidence(toolPaths, referenced);
        if (missing.isEmpty()) return null;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("detector", "workspace_evidence_coverage");
        details.put("missingEvidence", missing);
        return new QualityDetector.DetectionResult(true, "warning",
                "工具证据已命中真实源码文件，但当前输出未引用这些相对路径或仍声称未发现，请基于真实证据修订", details);
    }

    // =========================================================================
    //  辅助方法
    // =========================================================================

    private QualityReflectionFeedback toFeedback(QualityDetector.DetectionResult detection) {
        if (detection == null || !detection.hasIssue()) return null;
        StringBuilder prompt = new StringBuilder(
                "质量检测发现当前输出仍有问题，请直接修订并输出完整最终答案，不要解释过程，也不要继续调用工具。");
        prompt.append("\n- 严重级别: ").append(detection.severity());
        prompt.append("\n- 检测结论: ").append(detection.message());
        Map<String, Object> payload = new HashMap<>();
        payload.put("severity", detection.severity());
        payload.put("message", detection.message());
        if (detection.details() != null && !detection.details().isEmpty()) {
            payload.put("details", detection.details());
            detection.details().forEach((k, v) -> {
                if (v != null) prompt.append("\n  - ").append(k).append(": ").append(v);
            });
        }
        String compressedPrompt = prompt.length() > 1200
                ? prompt.substring(0, 1200) + "\n  - details: 已压缩展示，请优先修复上述问题"
                : prompt.toString();
        compressedPrompt = compressedPrompt + "\n请在保留已有有效信息的前提下修订输出。";
        payload.put("compressedPromptLength", compressedPrompt.length());
        return new QualityReflectionFeedback(detection.message(), compressedPrompt, payload);
    }

    private boolean canCheck(String content, int qualityReflectionCount, AgentEngineParams params) {
        return StringUtils.hasText(content)
                && params != null
                && qualityReflectionCount < params.getMaxQualityReflections();
    }

    private void checkPattern(Map<String, List<String>> issues, String content, String key, Pattern pattern, int limit) {
        List<String> matches = collectMatches(content, pattern, limit);
        if (!matches.isEmpty()) issues.put(key, matches);
    }

    private List<String> collectMatches(String content, Pattern pattern, int limit) {
        List<String> result = new ArrayList<>();
        Matcher m = pattern.matcher(content);
        while (m.find() && result.size() < limit) {
            String matched = m.groupCount() >= 1 && m.group(1) != null ? m.group(1) : m.group();
            if (StringUtils.hasText(matched)) result.add(matched);
        }
        return result;
    }

    private Set<String> extractFileReferences(String content) {
        Set<String> refs = new LinkedHashSet<>();
        Matcher m = AgentPatternConstant.WORKSPACE_FILE_REFERENCE.matcher(content);
        while (m.find() && refs.size() < 16) {
            String ref = normalizeRef(m.group(1));
            if (StringUtils.hasText(ref)) refs.add(ref);
        }
        return refs;
    }

    private String normalizeRef(String ref) {
        if (!StringUtils.hasText(ref)) return null;
        String s = ref.trim().replaceAll("^[`'\"(\\[]+", "").replaceAll("[`'\"),.;\\]]+$", "").replaceFirst("^\\./", "");
        return StringUtils.hasText(s) ? s : null;
    }

    private Set<String> extractToolReferencedPaths(ChatMemory chatMemory) {
        Set<String> refs = new LinkedHashSet<>();
        if (chatMemory == null) return refs;
        for (ChatMessage msg : chatMemory.messages()) {
            if (msg instanceof ToolExecutionResultMessage t && StringUtils.hasText(t.text())) {
                refs.addAll(extractFileReferences(t.text()));
            }
        }
        return refs;
    }

    private Map<String, List<String>> collectMissingEvidence(Set<String> toolPaths, Set<String> outputPaths) {
        Map<String, List<String>> missing = new LinkedHashMap<>();
        addMissingCategory(missing, "controllerApi",    toolPaths, outputPaths, this::isControllerOrApi);
        addMissingCategory(missing, "application",      toolPaths, outputPaths, this::isApplication);
        addMissingCategory(missing, "domainRepository", toolPaths, outputPaths, this::isDomainRepository);
        addMissingCategory(missing, "liquibase",        toolPaths, outputPaths, this::isLiquibase);
        return missing;
    }

    private void addMissingCategory(Map<String, List<String>> missing, String category,
                                     Set<String> toolPaths, Set<String> outputPaths,
                                     java.util.function.Predicate<String> matcher) {
        List<String> toolMatches = toolPaths.stream().filter(matcher).limit(3).toList();
        if (!toolMatches.isEmpty() && outputPaths.stream().noneMatch(matcher)) {
            missing.put(category, toolMatches);
        }
    }

    private boolean isControllerOrApi(String p) {
        String n = p.toLowerCase();
        return n.contains("/controller/") || n.contains("/api/") || n.endsWith("controller.java") || n.endsWith("api.java");
    }

    private boolean isApplication(String p) {
        String n = p.toLowerCase();
        return n.contains("/application/") && n.endsWith(".java");
    }

    private boolean isDomainRepository(String p) {
        String n = p.toLowerCase();
        return n.contains("/repository/") || n.contains("/aggregate/") || n.contains("/event/")
                || n.contains("/service/") || n.contains("/command/");
    }

    private boolean isLiquibase(String p) {
        String n = p.toLowerCase();
        return n.contains("liquibase/") || n.endsWith(".sql") || n.endsWith("changelog.xml") || n.endsWith("changelog-init.xml");
    }

    private boolean shouldCheckEvidenceCoverage(AgentExecutionContext ctx, String content) {
        return (StringUtils.hasText(content) && content.contains("当前仓库已确认现状"))
                || (ctx != null && StringUtils.hasText(ctx.getInputPrompt()) && ctx.getInputPrompt().contains("当前仓库已确认现状"));
    }

    private Path resolveWorkspaceRoot(AgentExecutionContext ctx) {
        if (ctx == null || ctx.getInputContext() == null) return null;
        Object wp = ctx.getInputContext().get("workspacePath");
        if (wp == null || !StringUtils.hasText(String.valueOf(wp))) return null;
        try {
            Path root = Path.of(String.valueOf(wp)).toAbsolutePath().normalize();
            return Files.isDirectory(root) ? root : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isExistingFile(Path root, String ref) {
        if (root == null || !StringUtils.hasText(ref)) return false;
        try {
            Path candidate = Path.of(ref);
            if (candidate.isAbsolute()) return false;
            Path resolved = root.resolve(candidate).normalize();
            return resolved.startsWith(root) && Files.exists(resolved);
        } catch (Exception e) {
            return false;
        }
    }
}
