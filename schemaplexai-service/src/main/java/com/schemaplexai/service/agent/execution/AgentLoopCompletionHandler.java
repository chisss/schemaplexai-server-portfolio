package com.schemaplexai.service.agent.execution;

import com.schemaplexai.common.constant.AgentLoopPromptConstant;
import com.schemaplexai.common.enums.AgentLoopLogTypeEnum;
import com.schemaplexai.service.ai.LangChain4jResolution;
import com.schemaplexai.service.quality.detector.QualityDetector;
import com.schemaplexai.service.quality.orchestrator.QualityOrchestrator;
import com.schemaplexai.service.workflow.ArtifactSceneResolver;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agentic Loop 收敛处理器
 *
 * <p>负责以下三类收敛场景：
 * <ol>
 *   <li>强制收敛（forceCompletion）：轮次/工具预算耗尽时，不带工具调用模型直接输出</li>
 *   <li>质量修订（reviseIfNecessary）：强制收敛后对输出做质量检测并修订</li>
 *   <li>降级收敛（buildDegraded）：模型调用与强制收敛均失败时，基于已有证据生成保守结论</li>
 * </ol>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLoopCompletionHandler {

    private final AgentModelInvoker modelInvoker;
    private final AgentLoopQualityChecker qualityChecker;
    private final QualityOrchestrator qualityOrchestrator;
    private final TokenEstimatorSupport tokenEstimatorSupport = new TokenEstimatorSupport();
    private final AgentChatMemoryCompactor chatMemoryCompactor = new AgentChatMemoryCompactor(tokenEstimatorSupport);

    // =========================================================================
    //  强制收敛
    // =========================================================================

    /**
     * 强制收敛：基于已收集证据，不带工具调用模型直接输出最终结果
     *
     * @throws Exception 模型调用失败时抛出，由调用方决定是否降级
     */
    public ChatResponse forceCompletion(LangChain4jResolution resolution,
                                        String systemPrompt,
                                        ChatMemory chatMemory,
                                        AgentEngineParams params,
                                        String executionId, String agentId, String tenantId,
                                        int round, long startMs,
                                        AgentLogService agentLogService,
                                        ExecutionEventStreamService eventStreamService) throws Exception {
        String context = buildForceCompletionContext(chatMemory, params);
        agentLogService.appendLog(executionId, agentId, tenantId, "INFO",
                AgentLoopLogTypeEnum.ROUND_START.getCode(), round, null,
                "强制收敛轮开始，evidence=" + collectFallbackEvidence(chatMemory, params).size() + " items",
                null, elapsed(startMs));
        eventStreamService.publishSimple(executionId, "ROUND_START", round, "进入强制收敛轮", startMs);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.add(UserMessage.from(context));

        ChatRequest request = ChatRequest.builder().messages(messages).toolSpecifications(List.of()).build();
        ChatResponse response = modelInvoker.invokeWithRetry(resolution, request, params,
                executionId, agentId, tenantId, round, startMs, agentLogService);

        if (response.aiMessage() != null && response.aiMessage().hasToolExecutionRequests()) {
            agentLogService.appendLog(executionId, agentId, tenantId, "WARN",
                    "UNEXPECTED_TOOL_REQUEST", round, null, "强制收敛轮仍返回工具调用，改为触发降级收敛", null, elapsed(startMs));
            eventStreamService.publishSimple(executionId, "UNEXPECTED_TOOL_REQUEST", round, "强制收敛轮仍返回工具调用", startMs);
            throw new IllegalStateException("强制收敛轮仍返回工具调用");
        }

        logAiResponse(response, params, executionId, agentId, tenantId, round, startMs, agentLogService);
        eventStreamService.publishSimple(executionId, "AI_RESPONSE", round, "已收到强制收敛响应", startMs);
        return response;
    }

    // =========================================================================
    //  质量修订
    // =========================================================================

    /**
     * 强制收敛后的质量修订：若检测到质量问题则追加一轮修订
     *
     * @return 修订结果（若无需修订则返回原内容）
     */
    public CompletionRevisionResult reviseIfNecessary(AgentExecutionContext ctx,
                                                       LangChain4jResolution resolution,
                                                       String systemPrompt,
                                                       ChatMemory chatMemory,
                                                       String content,
                                                       AgentEngineParams params,
                                                       String executionId, String agentId, String tenantId,
                                                       int round, long startMs,
                                                       AgentLogService agentLogService,
                                                       ExecutionEventStreamService eventStreamService) throws Exception {
        QualityReflectionFeedback feedback = qualityChecker.buildFeedback(ctx, content, chatMemory, 0, params);
        if (feedback == null) {
            return CompletionRevisionResult.noop(content, round - 1);
        }
        return reviseWithFeedback(resolution, systemPrompt, chatMemory, feedback, content, params,
                executionId, agentId, tenantId, round, startMs, agentLogService, eventStreamService);
    }

    /**
     * 基于已生成的质量反馈执行无工具修订
     */
    public CompletionRevisionResult reviseWithFeedback(LangChain4jResolution resolution,
                                                        String systemPrompt,
                                                        ChatMemory chatMemory,
                                                        QualityReflectionFeedback feedback,
                                                        String content,
                                                        AgentEngineParams params,
                                                        String executionId, String agentId, String tenantId,
                                                        int round, long startMs,
                                                        AgentLogService agentLogService,
                                                        ExecutionEventStreamService eventStreamService) throws Exception {
        if (feedback == null) {
            return CompletionRevisionResult.noop(content, round - 1);
        }
        agentLogService.appendLog(executionId, agentId, tenantId, "WARN",
                AgentLoopLogTypeEnum.QUALITY_FEEDBACK.getCode(), round, null,
                "强制收敛结果仍需修订，进入最终质检修订轮", null, elapsed(startMs));
        eventStreamService.publishWithPayload(executionId, "QUALITY_FEEDBACK", round,
                "强制收敛结果仍需修订", feedback.payload(), startMs);

        ChatResponse revisionResponse = requestRevisionWithoutTools(resolution, systemPrompt, chatMemory,
                feedback.prompt(), params, executionId, agentId, tenantId, round, startMs,
                agentLogService, eventStreamService);

        AiMessage revisionMsg = revisionResponse.aiMessage();
        if (revisionMsg != null && revisionMsg.hasToolExecutionRequests()) {
            agentLogService.appendLog(executionId, agentId, tenantId, "WARN",
                    "UNEXPECTED_TOOL_REQUEST", round, null, "最终质检修订轮仍返回工具调用，保留当前强制收敛结果", null, elapsed(startMs));
            return CompletionRevisionResult.noop(content, round - 1);
        }

        String revised = revisionMsg != null && StringUtils.hasText(revisionMsg.text()) ? revisionMsg.text() : content;
        chatMemory.add(UserMessage.from(feedback.prompt()));
        if (revisionMsg != null) chatMemory.add(revisionMsg);

        long tokenIn  = revisionResponse.metadata().tokenUsage() != null ? revisionResponse.metadata().tokenUsage().inputTokenCount()  : 0;
        long tokenOut = revisionResponse.metadata().tokenUsage() != null ? revisionResponse.metadata().tokenUsage().outputTokenCount() : 0;
        return new CompletionRevisionResult(revised, tokenIn, tokenOut, round, 1);
    }

    // =========================================================================
    //  降级收敛
    // =========================================================================

    /**
     * 降级收敛：模型调用与强制收敛均失败时，基于已有证据生成保守结论
     */
    public String buildDegraded(AgentExecutionContext ctx, ChatMemory chatMemory,
                                 int round, Exception primaryEx, Exception forcedEx,
                                 AgentEngineParams params) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 阶段性技术结论（降级收敛）\n\n");
        sb.append("## 任务目标\n- ")
          .append(compactText(ctx != null ? ctx.getInputPrompt() : "生成本节点要求的结构化 Markdown 结果", 220))
          .append("\n\n");

        sb.append("## 已确认信息\n");
        List<String> evidences = collectFallbackEvidence(chatMemory, params);
        if (evidences.isEmpty()) {
            sb.append("- 当前未收集到足够证据，请在后续节点或人工复核时补充。\n");
        } else {
            evidences.forEach(e -> sb.append("- ").append(e).append("\n"));
        }
        sb.append("\n## 当前结论\n");
        sb.append("- 已基于前序轮次收集的信息完成收敛输出，可继续驱动后续工作流节点。\n");
        sb.append("- 模型在第 ").append(round).append(" 轮发生瞬时异常，当前结果为保守结论，优先保证链路连续性。\n\n");

        sb.append("## 风险与待确认\n");
        sb.append("- 模型异常: ").append(compactText(resolveExMsg(primaryEx), 180)).append("\n");
        if (forcedEx != null) {
            sb.append("- 强制收敛异常: ").append(compactText(resolveExMsg(forcedEx), 180)).append("\n");
        }
        sb.append("- 如需更完整结论，可在模型恢复后重新触发当前节点或复跑工作流。\n");
        return sb.toString();
    }

    // =========================================================================
    //  证据收集（供外部调用）
    // =========================================================================

    /**
     * 从 ChatMemory 中收集可用于降级收敛的证据列表
     */
    public List<String> collectFallbackEvidence(ChatMemory chatMemory, AgentEngineParams params) {
        List<String> toolEvidences = new ArrayList<>();
        List<String> aiEvidences   = new ArrayList<>();
        if (chatMemory == null || chatMemory.messages() == null) return toolEvidences;

        List<ChatMessage> messages = chatMemory.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof ToolExecutionResultMessage t && StringUtils.hasText(t.text())
                    && toolEvidences.size() < params.getFallbackEvidenceLimit()) {
                toolEvidences.addFirst("工具[" + t.toolName() + "] => " + compactText(t.text(), params.getFallbackTextLimit()));
            } else if (msg instanceof AiMessage a && StringUtils.hasText(a.text())
                    && aiEvidences.size() < params.getFallbackEvidenceLimit()) {
                aiEvidences.addFirst("AI结论 => " + compactText(a.text(), params.getFallbackTextLimit()));
            }
        }
        return !toolEvidences.isEmpty() ? toolEvidences : aiEvidences;
    }

    /**
     * 判断 ChatMemory 中是否存在足够的收敛证据（至少 2 条有效消息）
     */
    public boolean hasCompletionEvidence(ChatMemory chatMemory) {
        if (chatMemory == null || chatMemory.messages() == null) return false;
        int count = 0;
        for (ChatMessage msg : chatMemory.messages()) {
            if ((msg instanceof AiMessage a && StringUtils.hasText(a.text()))
                    || (msg instanceof ToolExecutionResultMessage t && StringUtils.hasText(t.text()))) {
                if (++count >= 2) return true;
            }
        }
        return false;
    }

    // =========================================================================
    //  私有辅助方法
    // =========================================================================

    private ChatResponse requestRevisionWithoutTools(LangChain4jResolution resolution,
                                                      String systemPrompt,
                                                      ChatMemory chatMemory,
                                                      String revisionPrompt,
                                                      AgentEngineParams params,
                                                      String executionId, String agentId, String tenantId,
                                                      int round, long startMs,
                                                      AgentLogService agentLogService,
                                                      ExecutionEventStreamService eventStreamService) throws Exception {
        agentLogService.appendLog(executionId, agentId, tenantId, "INFO",
                AgentLoopLogTypeEnum.ROUND_START.getCode(), round, null,
                "最终质检修订轮开始，history=" + chatMemory.messages().size() + " msgs, effectiveTools=0",
                null, elapsed(startMs));
        eventStreamService.publishSimple(executionId, "ROUND_START", round, "进入最终质检修订轮", startMs);
        chatMemoryCompactor.compactIfNeeded(chatMemory, params, resolution != null ? resolution.config() : null);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.addAll(chatMemory.messages());
        messages.add(UserMessage.from(revisionPrompt));

        ChatRequest request = ChatRequest.builder().messages(messages).toolSpecifications(List.of()).build();
        ChatResponse response = modelInvoker.invokeWithRetry(resolution, request, params,
                executionId, agentId, tenantId, round, startMs, agentLogService);

        logAiResponse(response, params, executionId, agentId, tenantId, round, startMs, agentLogService);
        eventStreamService.publishSimple(executionId, "AI_RESPONSE", round, "已收到最终质检修订响应", startMs);
        return response;
    }

    private String buildForceCompletionContext(ChatMemory chatMemory, AgentEngineParams params) {
        StringBuilder sb = new StringBuilder();
        String taskSummary = extractTaskSummary(chatMemory);
        boolean customerDeliveryContext = ArtifactSceneResolver.isCustomerDeliveryPrompt(taskSummary);
        sb.append("请基于以下已收集证据直接输出最终 Markdown，不要再调用工具。\n\n");
        sb.append("输出约束:\n");
        if (customerDeliveryContext) {
            sb.append("- 最终文档必须直接面向客户或业务负责人，优先输出已确认事实、交付方案、交付清单、业务价值和风险边界。\n")
                    .append("- 不要输出内部仓库审计标题、缺失占位语或回填说明。\n")
                    .append("- 运行时回填字段如当前未提供，直接省略；未知项统一放入“风险与边界”或“待确认事项”。\n\n");
        } else {
            sb.append("- 只允许把证据里明确出现的文件路径、类名、接口、SQL 信息写成\"当前仓库已确认现状\"。\n")
                    .append("- 如果证据只显示目录或文件名存在，只能据此说明\"存在该目录/文件\"，不能推断方法、注解或实现细节。\n")
                    .append("- 最终文档必须区分\"当前仓库已确认现状\"和\"建议改造/待实现项\"。\n")
                    .append("- 证据中没有出现的路径或类名请写\"仓库中未发现\"或\"待确认\"，不要猜测。\n\n");
        }

        if (StringUtils.hasText(taskSummary)) {
            sb.append("任务摘要: ").append(compactText(taskSummary, 220)).append("\n\n");
        }

        List<String> evidences = collectFallbackEvidence(chatMemory, params);
        if (evidences.isEmpty()) {
            sb.append(customerDeliveryContext
                    ? "- 当前没有可用证据，请直接输出一个保守但可交付的客户方案，并明确风险与待确认事项。"
                    : "- 当前没有可用证据，请直接输出一个保守但可交付的 Markdown 结果，并明确待确认项。");
        } else {
            sb.append("已收集证据:\n");
            evidences.forEach(e -> sb.append("- ").append(e).append("\n"));
            sb.append("\n").append(AgentLoopPromptConstant.FORCE_COMPLETION);
        }
        return sb.toString();
    }

    private String extractTaskSummary(ChatMemory chatMemory) {
        if (chatMemory == null || chatMemory.messages() == null) return null;
        for (ChatMessage msg : chatMemory.messages()) {
            if (msg instanceof UserMessage u && StringUtils.hasText(u.singleText())) {
                return u.singleText();
            }
        }
        return null;
    }

    private void logAiResponse(ChatResponse response, AgentEngineParams params,
                                String executionId, String agentId, String tenantId,
                                int round, long startMs, AgentLogService agentLogService) {
        String preview = response.aiMessage() != null && response.aiMessage().text() != null
                ? response.aiMessage().text() : "";
        if (preview.length() > params.getLogContentLimit()) {
            preview = preview.substring(0, params.getLogContentLimit()) + "...[截断]";
        }
        int outputTokens = response.metadata().tokenUsage() != null
                ? (int) response.metadata().tokenUsage().outputTokenCount() : 0;
        agentLogService.appendLog(executionId, agentId, tenantId, "INFO",
                AgentLoopLogTypeEnum.AI_RESPONSE.getCode(), round, null, preview, outputTokens, elapsed(startMs));
    }

    private String compactText(String text, int limit) {
        if (!StringUtils.hasText(text)) return "待确认";
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, Math.max(limit, 1)) + "...";
    }

    private String resolveExMsg(Exception e) {
        return e != null && StringUtils.hasText(e.getMessage()) ? e.getMessage()
                : e != null ? e.getClass().getSimpleName() : "unknown";
    }

    private long elapsed(long startMs) {
        return System.currentTimeMillis() - startMs;
    }

    /** 质量反思反馈 */
    public record QualityReflectionFeedback(String message, String prompt, Map<String, Object> payload) {}

    /** 收敛修订结果 */
    public record CompletionRevisionResult(String content, long tokenInput, long tokenOutput,
                                           int finalRound, int roundsUsed) {
        public static CompletionRevisionResult noop(String content, int finalRound) {
            return new CompletionRevisionResult(content, 0, 0, finalRound, 0);
        }
    }
}
