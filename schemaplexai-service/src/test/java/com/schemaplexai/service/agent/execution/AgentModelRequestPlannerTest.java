package com.schemaplexai.service.agent.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.util.HashUtils;
import com.schemaplexai.service.ai.AiModelConfig;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import static com.schemaplexai.service.agent.tool.langchain4j.LangChain4jBindingToolExecutor.DESKTOP_CALL_ID;
import static com.schemaplexai.service.agent.tool.langchain4j.LangChain4jBindingToolExecutor.DESKTOP_MAVEN_MODEL_SUMMARY;
import static com.schemaplexai.service.agent.tool.langchain4j.LangChain4jBindingToolExecutor.DESKTOP_READ_EVIDENCE_HASH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentModelRequestPlannerTest {
    private final AgentModelRequestPlanner planner = new AgentModelRequestPlanner(new TokenEstimatorSupport());

    @Test
    void shouldTrimOldWholeTurnsOnlyForSmallerCandidate() {
        var system = SystemMessage.from("系统指令");
        var history = UserMessage.from("历史上下文".repeat(120));
        var tool = ToolExecutionRequest.builder().id("previous").name("lookup").arguments("{}").build();
        var assistant = AiMessage.from(tool);
        var result = ToolExecutionResultMessage.from(tool, "历史结果");
        var latest = UserMessage.from("当前任务");
        var request = ChatRequest.builder().messages(system, history, assistant, result, latest).build();

        var shrunk = planner.fit(request, model(500, false), Long.MAX_VALUE);
        var roomy = planner.fit(request, model(3000, false), Long.MAX_VALUE);

        assertThat(shrunk.messages()).containsExactly(system, latest);
        assertThat(roomy.messages()).containsExactlyElementsOf(request.messages());
        assertThat(shrunk.maxOutputTokens()).isPositive().isLessThan(500);
    }

    @Test
    void shouldRejectTextOnlyCandidateAndKeepImageForVisionCandidate() {
        var latest = UserMessage.from(TextContent.from("描述附件"), ImageContent.from("aW1hZ2U=", "image/png"));
        var request = ChatRequest.builder().messages(latest).build();

        assertThatThrownBy(() -> planner.fit(request, model(4000, false), Long.MAX_VALUE))
                .isInstanceOf(AgentModelRequestPlanner.IncompatibleCandidateException.class)
                .hasMessageContaining("多模态");
        assertThat(planner.fit(request, model(4000, true), Long.MAX_VALUE).messages()).containsExactly(latest);
    }

    @Test
    void shouldBudgetForToolSchemasAndPreserveCurrentTask() {
        var tool = ToolSpecification.builder().name("lookup").description("需要很长的说明".repeat(100)).build();
        var request = ChatRequest.builder().messages(UserMessage.from("当前任务")).toolSpecifications(tool).build();

        assertThatThrownBy(() -> planner.fit(request, model(512, false), Long.MAX_VALUE))
                .isInstanceOf(AgentModelRequestPlanner.IncompatibleCandidateException.class)
                .hasMessageContaining("工具定义");
    }

    @Test
    void shouldDistinguishCumulativeBudgetFromModelWindow() {
        var currentTask = UserMessage.from("必须完整保留本轮任务与验收项".repeat(40));
        var request = ChatRequest.builder().messages(currentTask).build();

        assertThatThrownBy(() -> planner.fit(request, model(8000, false), 140))
                .isInstanceOfSatisfying(AgentModelRequestPlanner.IncompatibleCandidateException.class, error -> {
                    assertThat(error.isTokenBudgetLimited()).isTrue();
                    assertThat(error).hasMessageContaining("剩余 Token 预算不足");
                });
        assertThatThrownBy(() -> planner.fit(request, model(140, false), 8000))
                .isInstanceOfSatisfying(AgentModelRequestPlanner.IncompatibleCandidateException.class, error -> {
                    assertThat(error.isTokenBudgetLimited()).isFalse();
                    assertThat(error).hasMessageContaining("候选模型窗口");
                });
    }

    @Test
    void shouldProjectOnlyGeneratedTaskSectionWithCurrentAuthoredMessage() {
        var authored = UserMessage.from("Java 任务正文\n\n## 当前任务上下文\n正文中的同名标题");
        String stableSystem = "可信规则\n\n---\n\n## 当前任务上下文\n独立系统指令";
        String desktopSystem = "\n\n## 本机工作区执行边界\n限定项目目录";
        String taskSection = taskSection(authored);
        var canonical = SystemMessage.builder().text(stableSystem + taskSection + desktopSystem)
                .attributes(Map.of("other", "preserved")).build();
        var tagged = AgentModelRequestPlanner.markDesktopEvidenceProjection(
                markTaskContext(canonical, authored, stableSystem.length()), authored);
        var tool = ToolSpecification.builder().name("lookup").description("按需查询").build();
        var call = read("task-read");
        var response = ToolExecutionResultMessage.from(call, "项目文件");
        var request = ChatRequest.builder().messages(tagged, authored, AiMessage.from(call), response)
                .toolSpecifications(tool).build();

        var fitted = planner.fit(request, model(8000, false), Long.MAX_VALUE);

        assertThat(fitted.messages()).containsExactly(canonical.toBuilder().text(stableSystem + desktopSystem).build(),
                authored, AiMessage.from(call), response);
        assertThat(fitted.toolSpecifications()).containsExactly(tool);
        assertThat(request.messages().getFirst()).isSameAs(tagged);
        assertThat(tagged.text()).isEqualTo(canonical.text());
    }

    @Test
    void shouldRetainSystemSectionForSameTextHistoryWithoutAuthoredIdentity() {
        var authored = UserMessage.from("Java 任务正文");
        var historical = UserMessage.from(authored.singleText());
        String base = "系统指令";
        var canonical = SystemMessage.from(base + taskSection(authored));
        var tagged = AgentModelRequestPlanner.markDesktopEvidenceProjection(
                markTaskContext(canonical, authored, base.length()), authored);
        var request = ChatRequest.builder().messages(tagged, historical).build();

        assertThat(planner.fit(request, model(8000, false), Long.MAX_VALUE).messages())
                .containsExactly(canonical, historical);
        assertThat(planner.fit(request, null, Long.MAX_VALUE).messages()).containsExactly(canonical, historical);
        assertThat(planner.fit(request.toBuilder().messages(tagged, authored).build(), null, Long.MAX_VALUE).messages())
                .containsExactly(canonical, authored);
    }

    @Test
    void shouldRestoreSystemSectionAfterPlannerDiscardedAuthoredTurn() {
        var authored = UserMessage.from("执行完整任务".repeat(20));
        var latest = UserMessage.from("补充指令");
        String base = "系统指令";
        var canonical = SystemMessage.from(base + taskSection(authored));
        var tagged = AgentModelRequestPlanner.markDesktopEvidenceProjection(
                markTaskContext(canonical, authored, base.length()), authored);
        var oldCall = read("old-read");
        var oldResult = ToolExecutionResultMessage.from(oldCall, "已归档旧工具输出".repeat(100));
        var currentCall = read("current-read");
        var currentResult = ToolExecutionResultMessage.from(currentCall, "当前工具输出");
        var tool = ToolSpecification.builder().name("sys_read").build();
        var retained = List.of(canonical, latest, AiMessage.from(currentCall), currentResult);
        var request = ChatRequest.builder().messages(tagged, authored, AiMessage.from(oldCall), oldResult,
                latest, AiMessage.from(currentCall), currentResult).toolSpecifications(tool).build();
        var config = model(8000, false);
        long canonicalTokens = planner.estimateInput(config, List.copyOf(retained), List.of(tool));

        var fitted = planner.fit(request, config, canonicalTokens + 192);

        assertThat(fitted.messages()).containsExactlyElementsOf(retained);
        assertThat(fitted.toolSpecifications()).containsExactly(tool);
        assertThat(fitted.maxOutputTokens()).isGreaterThanOrEqualTo(128);
        assertThatThrownBy(() -> planner.fit(request, config, canonicalTokens + 128))
                .isInstanceOf(AgentModelRequestPlanner.IncompatibleCandidateException.class);
    }

    @Test
    void shouldKeepCanonicalSystemForInvalidRangeOrMultimodalAuthoredMessage() {
        var authored = UserMessage.from("任务正文");
        String base = "系统规则";
        var canonical = SystemMessage.from(base + taskSection(authored));
        var wrongRange = new ContextInjector.TaskContextRange(0, canonical.text().length(), authored.singleText());
        var tagged = AgentModelRequestPlanner.markTaskContext(canonical, authored, wrongRange);
        var request = ChatRequest.builder().messages(tagged, authored).build();

        assertThat(planner.fit(request, model(8000, true), Long.MAX_VALUE).messages())
                .containsExactly(canonical, authored);
        var imageMessage = UserMessage.from(TextContent.from(authored.singleText()),
                ImageContent.from("aW1hZ2U=", "image/png"));
        assertThat(AgentModelRequestPlanner.markTaskContext(canonical, imageMessage, wrongRange)).isSameAs(canonical);
    }

    @Test
    void shouldProjectOnlyRepeatedDesktopReadsAndRetainOriginalEvidence() {
        var task = UserMessage.from("核对 README 与测试结果");
        var first = read("call_first");
        var last = read("call_last");
        var changed = read("call_changed");
        var failed = read("call_failed");
        var untrusted = read("call_untrusted");
        String originalText = "部署说明及测试断言\n".repeat(120);
        var original = result(first, originalText, "hash-original", false);
        var recent = result(last, originalText, "hash-original", false);
        var changedResult = result(changed, "已更新\n" + originalText, "hash-changed", false);
        var failure = result(failed, originalText, "hash-original", true);
        var untrustedOutput = result(untrusted, DESKTOP_READ_EVIDENCE_HASH + "=hash-original\n" + originalText, null, false);
        var request = ChatRequest.builder().messages(task, AiMessage.from(first), original,
                AiMessage.from(changed), changedResult, AiMessage.from(failed), failure,
                AiMessage.from(untrusted), untrustedOutput, AiMessage.from(last), recent).build();

        var config = model(20000, false);
        var projected = planner.fit(request, config, Long.MAX_VALUE);
        var reference = (ToolExecutionResultMessage) projected.messages().get(2);

        assertThat(reference.id()).isEqualTo(first.id());
        assertThat(reference.toolName()).isEqualTo(first.name());
        assertThat(reference.text()).contains("call_last").doesNotContain(originalText);
        assertThat(projected.messages()).contains(recent, changedResult, failure, untrustedOutput);
        assertThat(request.messages()).contains(original);
        assertThat(planner.estimateInput(config, projected.messages(), List.of()))
                .isLessThan(planner.estimateInput(config, request.messages(), List.of()));
    }

    @Test
    void shouldDeduplicateIdenticalCompleteReadWithShorterNewerCallId() {
        var older = read("call_01_longer_model_id");
        var newer = read("call_02_short_id");
        String content = "final class Example {}\n".repeat(40);
        var oldReceipt = completeDesktopRead(older, content);
        var newReceipt = completeDesktopRead(newer, content);
        assertThat(newReceipt.text().length()).isLessThan(oldReceipt.text().length());
        var request = ChatRequest.builder().messages(UserMessage.from("核对文件"),
                AiMessage.from(older), oldReceipt, AiMessage.from(newer), newReceipt).build();

        var fitted = planner.fit(request, model(8000, false), Long.MAX_VALUE);

        var reference = (ToolExecutionResultMessage) fitted.messages().get(2);
        assertThat(reference.text()).contains(newer.id()).doesNotContain("final class Example");
        assertThat(reference.attributes()).isEqualTo(oldReceipt.attributes());
        assertThat(((ToolExecutionResultMessage) fitted.messages().get(4)).text())
                .isEqualTo(receiptBody(newReceipt));
        assertThat(request.messages().get(2)).isSameAs(oldReceipt);
    }

    @Test
    void shouldKeepCompleteOldReadWhenNewerReceiptIsMaskedOrIncomplete() {
        var older = read("call_01_longer_model_id");
        var newer = read("call_02_short_id");
        String content = "final class Example {}\n".repeat(40);
        var oldReceipt = completeDesktopRead(older, content);
        var newReceipt = completeDesktopRead(newer, content);
        var masked = newReceipt.toBuilder()
                .contents(TextContent.from(newReceipt.text().substring(0, 400) + "\n[已压缩，原始长度: 962 字符]"))
                .build();
        var incomplete = newReceipt.toBuilder()
                .contents(TextContent.from(newReceipt.text().substring(0, newReceipt.text().length() - 8))).build();
        var compressedAttributes = new HashMap<>(newReceipt.attributes());
        compressedAttributes.put("compressionApplied", true);
        var compressed = newReceipt.toBuilder().attributes(compressedAttributes).build();
        var differentText = completeDesktopRead(newer, content + "已变化");
        for (var replacement : List.of(masked, incomplete, compressed, differentText)) {
            var request = ChatRequest.builder().messages(UserMessage.from("核对文件"),
                    AiMessage.from(older), oldReceipt, AiMessage.from(newer), replacement).build();
            var fitted = planner.fit(request, model(8000, false), Long.MAX_VALUE);

            assertThat(((ToolExecutionResultMessage) fitted.messages().get(2)).text())
                    .isEqualTo(receiptBody(oldReceipt));
        }
    }

    private ToolExecutionResultMessage completeDesktopRead(ToolExecutionRequest call, String result) {
        String body = new ObjectMapper().createObjectNode().put("callId", call.id())
                .put("toolCode", "sys.read").put("success", true).put("result", result)
                .putNull("errorMessage").putNull("controlAction").toString();
        return ToolExecutionResultMessage.builder().id(call.id()).toolName(call.name()).isError(false)
                .text("请求参数: " + call.arguments() + "\n工具结果: " + body)
                .attributes(Map.of(DESKTOP_CALL_ID, "00000000-0000-4000-8000-000000000001",
                        DESKTOP_READ_EVIDENCE_HASH, "same-read-evidence", "compressionApplied", false,
                        "rawChars", body.length(), "compressedChars", body.length())).build();
    }

    @Test
    void shouldUseDesktopMavenSummaryOnlyInOutgoingModelRequest() {
        var call = ToolExecutionRequest.builder().id("maven-1").name("sys_bash")
                .arguments("{\"command\":\"mvn -B package\"}").build();
        String raw = "Maven full build log\n".repeat(120);
        String summary = "{\"command\":\"mvn -B package\",\"exitCode\":0,\"tests\":12,"
                + "\"failures\":0,\"errors\":0,\"skipped\":0,\"logEnd\":\"BUILD SUCCESS\"}";
        var tagged = ToolExecutionResultMessage.builder().id(call.id()).toolName(call.name())
                .isError(false).text(raw).attributes(Map.of(DESKTOP_MAVEN_MODEL_SUMMARY, summary)).build();
        var untagged = tagged.toBuilder().contents(TextContent.from(summary + "\n" + raw))
                .attributes(Map.of()).build();
        var failed = tagged.toBuilder().isError(true).build();
        var shortResult = tagged.toBuilder().contents(TextContent.from("短日志")).build();
        var otherTool = tagged.toBuilder().toolName("another_tool").build();
        var request = ChatRequest.builder().messages(UserMessage.from("验证构建"),
                AiMessage.from(call), tagged, untagged, failed, shortResult, otherTool).build();
        var config = model(20000, false);
        var fitted = planner.fit(request, config, Long.MAX_VALUE);
        var projected = (ToolExecutionResultMessage) fitted.messages().get(2);

        assertThat(projected.id()).isEqualTo(tagged.id());
        assertThat(projected.toolName()).isEqualTo(tagged.toolName());
        assertThat(projected.text()).contains(summary).doesNotContain(raw);
        assertThat(projected.attributes()).doesNotContainKey(DESKTOP_MAVEN_MODEL_SUMMARY);
        assertThat(fitted.messages()).contains(untagged, failed, shortResult, otherTool);
        assertThat(tagged.text()).isEqualTo(raw);
        assertThat(request.messages().get(2)).isSameAs(tagged);
        assertThat(planner.estimateInput(config, fitted.messages(), List.of()))
                .isLessThan(planner.estimateInput(config, request.messages(), List.of()));
    }

    @Test
    void shouldProjectVerifiedMavenFailureAndPreserveErrorStatus() {
        String raw = "Downloading from central\n".repeat(280);
        String diagnostic = "{\"exitCode\":1,\"errors\":\"[ERROR] QuoteTest.java:[24,17] cannot find symbol\"}";
        var original = ToolExecutionResultMessage.builder().id("maven-1").toolName("sys_bash").isError(true)
                .text(raw).attributes(Map.of(DESKTOP_MAVEN_MODEL_SUMMARY, diagnostic,
                        DESKTOP_CALL_ID, "11bce843-ad43-4538-bb1a-2edcfaa82e12")).build();
        var unverified = original.toBuilder().attributes(Map.of(DESKTOP_MAVEN_MODEL_SUMMARY, diagnostic)).build();
        var call = ToolExecutionRequest.builder().id(original.id()).name(original.toolName())
                .arguments("{\"command\":\"mvn clean package\"}").build();
        var request = ChatRequest.builder().messages(UserMessage.from("验证构建"), AiMessage.from(call), original).build();

        var fitted = planner.fit(request, model(20_000, false), Long.MAX_VALUE);

        assertThat(fitted.messages().getLast()).isInstanceOfSatisfying(ToolExecutionResultMessage.class, output -> {
            assertThat(output.text()).contains(diagnostic).doesNotContain("Downloading from central");
            assertThat(output.isError()).isTrue();
            assertThat(output.id()).isEqualTo(call.id());
        });
        assertThat(AgentModelRequestPlanner.desktopMavenProjection(unverified)).isNull();
        assertThat(((ToolExecutionResultMessage) request.messages().getLast()).text()).isEqualTo(raw);
    }

    @Test
    void shouldProjectOnlySupersededDesktopWritesOutsideTwoRecentExchanges() {
        var task = UserMessage.from("迭代项目文件");
        var old = write("old", "sys_write", "src/App.java", ".", "旧代码");
        var unique = write("unique", "sys_write", "src/Other.java", ".", "只写入一次");
        var oldExchange = AiMessage.builder().text("更新文件").thinking("保留推理签名")
                .attributes(Map.of("fixture", "metadata")).toolExecutionRequests(List.of(old, unique)).build();
        var oldReceipt = writeReceipt(old, false, "00000000-0000-4000-8000-000000000001");
        var uniqueReceipt = writeReceipt(unique, false, "00000000-0000-4000-8000-000000000002");
        var recent = write("recent", "sys_edit", "src/App.java", null, "最近写入");
        var latest = write("latest", "sys_edit", "src/App.java", ".", "最终写入");
        var request = ChatRequest.builder().messages(task, oldExchange, oldReceipt, uniqueReceipt,
                AiMessage.from(recent), writeReceipt(recent, false, "00000000-0000-4000-8000-000000000003"),
                AiMessage.from(latest), writeReceipt(latest, false, "00000000-0000-4000-8000-000000000004")).build();
        var config = model(24_000, false);

        var projected = planner.fit(request, config, Long.MAX_VALUE);
        var changedExchange = (AiMessage) projected.messages().get(1);
        var compact = changedExchange.toolExecutionRequests().getFirst();

        assertThat(compact.id()).isEqualTo(old.id());
        assertThat(compact.name()).isEqualTo(old.name());
        assertThat(compact.arguments()).contains("src/App.java", "workingDirectory", "后续完整写入见工具调用 latest")
                .doesNotContain("旧代码");
        assertThat(changedExchange.toolExecutionRequests()).containsExactly(compact, unique);
        assertThat(changedExchange.text()).isEqualTo(oldExchange.text());
        assertThat(changedExchange.thinking()).isEqualTo(oldExchange.thinking());
        assertThat(changedExchange.attributes()).isEqualTo(oldExchange.attributes());
        assertThat(projected.messages().subList(2, projected.messages().size()))
                .containsExactlyElementsOf(request.messages().subList(2, request.messages().size()));
        assertThat(request.messages().get(1)).isSameAs(oldExchange);
        assertThat(planner.estimateInput(config, projected.messages(), List.of()))
                .isLessThan(planner.estimateInput(config, request.messages(), List.of()));
    }

    @Test
    void shouldKeepWritesWithIncompleteReceiptsAndAcrossWorkspaceTasks() {
        var prior = write("prior", "sys_write", "src/App.java", ".", "前一轮源码");
        var old = write("old", "sys_write", "src/App.java", ".", "旧源码");
        var missing = write("missing", "sys_write", "src/Other.java", ".", "缺少回执");
        var cloud = write("cloud", "sys_write", "src/App.java", ".", "非本机源码");
        var otherDirectory = write("other", "sys_edit", "src/App.java", "sub", "其他目录");
        var failed = write("failed", "sys_edit", "src/App.java", ".", "失败写入");
        var latest = write("latest", "sys_edit", "src/App.java", ".", "最终写入");
        var request = ChatRequest.builder().messages(UserMessage.from("在另一授权目录写入"),
                AiMessage.from(prior), writeReceipt(prior, false, "00000000-0000-4000-8000-000000000001"),
                UserMessage.from("在当前授权目录写入"),
                AiMessage.from(old, missing), writeReceipt(old, false, "00000000-0000-4000-8000-000000000002"),
                AiMessage.from(cloud), writeReceipt(cloud, false, null),
                AiMessage.from(otherDirectory), writeReceipt(otherDirectory, false, "00000000-0000-4000-8000-000000000003"),
                AiMessage.from(failed), writeReceipt(failed, true, "00000000-0000-4000-8000-000000000004"),
                AiMessage.from(latest), writeReceipt(latest, false, "00000000-0000-4000-8000-000000000005")).build();

        assertThat(planner.fit(request, model(32_000, false), Long.MAX_VALUE).messages())
                .containsExactlyElementsOf(request.messages());
    }

    @Test
    void shouldProjectHistoricalDesktopEditAndWriteWhenLaterFullReadProvesContent() throws Exception {
        var old = write("old-write", "sys_write", "src/App.java", ".", "确认后的代码");
        var edit = exactEdit("old-edit", "Other.java", "src");
        var writeRead = fileRead("write-read", "./src/App.java");
        var editRead = fileRead("edit-read", "src/Other.java");
        var newRead = fileRead("recent-read", "./src/App.java");
        var oldReceipt = successfulWriteReceipt(old, "sys.write", "src/App.java");
        var editReceipt = successfulWriteReceipt(edit, "sys.edit", "Other.java");
        var writeBody = new ObjectMapper().readTree(old.arguments()).path("content").textValue();
        var editBody = new ObjectMapper().readTree(edit.arguments()).path("newText").textValue();
        var request = ChatRequest.builder().messages(UserMessage.from("迭代项目文件"),
                AiMessage.from(old, edit), oldReceipt, editReceipt,
                AiMessage.from(writeRead, editRead), verifiedRead(writeRead, writeBody),
                verifiedRead(editRead, "更新后的完整文件\n" + editBody),
                AiMessage.from(newRead), verifiedRead(newRead, writeBody)).build();
        var config = model(24_000, false);

        var projected = planner.fit(request, config, Long.MAX_VALUE);

        assertThat(((AiMessage) projected.messages().get(1)).toolExecutionRequests())
                .satisfiesExactly(write -> {
                    assertThat(write.id()).isEqualTo(old.id());
                    assertThat(write.arguments()).contains("src/App.java", "recent-read").doesNotContain("确认后的代码");
                }, edited -> {
                    assertThat(edited.id()).isEqualTo(edit.id());
                    assertThat(edited.arguments()).contains("Other.java", "edit-read")
                            .doesNotContain("旧源码片段", "新源码片段");
                });
        assertThat(((ToolExecutionResultMessage) projected.messages().get(2)).id()).isEqualTo(old.id());
        assertThat(((ToolExecutionResultMessage) projected.messages().get(3)).id()).isEqualTo(edit.id());
        assertThat(((AiMessage) request.messages().get(1)).toolExecutionRequests()).containsExactly(old, edit);
        assertThat(planner.estimateInput(config, projected.messages(), List.of()))
                .isLessThan(planner.estimateInput(config, request.messages(), List.of()));
    }

    @Test
    void shouldReplaceOnlyVerifiedHistoricalFullReadsSupersededByASavedBody() throws Exception {
        var old = fileRead("old-read", "./src/App.java");
        var partial = old.toBuilder().id("partial-read").arguments("{\"path\":\"src/App.java\",\"endLine\":12}").build();
        var unrelated = fileRead("unrelated", "src/Other.java");
        var write = write("new-write", "sys_write", "App.java", "src", "新版正文");
        var recent = fileRead("recent-read", "src/App.java");
        String original = "旧版全文\n".repeat(120);
        var full = verifiedRead(old, original);
        var range = verifiedRead(partial, "[文件片段]\n" + original);
        var other = verifiedRead(unrelated, original);
        var latest = verifiedRead(recent, "新版正文".repeat(320));
        var request = ChatRequest.builder().messages(UserMessage.from("迭代源文件"), AiMessage.from(old, partial, unrelated),
                full, range, other, AiMessage.from(write), successfulWriteReceipt(write, "sys.write", "App.java"),
                AiMessage.from(recent), latest).build();

        var config = model(24_000, false);
        var projected = planner.fit(request, config, Long.MAX_VALUE);
        var historical = (ToolExecutionResultMessage) projected.messages().get(2);
        String escapedOriginal = original.replace("\n", "\\n");
        assertThat(historical.id()).isEqualTo(old.id());
        assertThat(historical.text()).contains("写入前版本", "new-write").doesNotContain(escapedOriginal);
        assertThat(((ToolExecutionResultMessage) projected.messages().get(3)).text()).contains("[文件片段]");
        assertThat(((ToolExecutionResultMessage) projected.messages().get(4)).text()).contains(escapedOriginal);
        assertThat(((AiMessage) projected.messages().get(5)).toolExecutionRequests()).containsExactly(write);
        assertThat(((ToolExecutionResultMessage) projected.messages().getLast()).text()).contains("新版正文".repeat(320));
        assertThat(request.messages().get(2)).isSameAs(full);
        assertThat(planner.estimateInput(config, projected.messages(), List.of()))
                .isLessThan(planner.estimateInput(config, request.messages(), List.of()));

        var shell = ToolExecutionRequest.builder().id("shell-1").name("sys_bash")
                .arguments("{\"command\":\"mvn package\"}").build();
        var afterShell = request.toBuilder().messages(UserMessage.from("迭代源文件"), AiMessage.from(old), full,
                AiMessage.from(shell), desktopReceipt(shell, "sys.bash", shell.arguments(), false),
                AiMessage.from(write), successfulWriteReceipt(write, "sys.write", "App.java"),
                AiMessage.from(recent), latest).build();
        assertThat(((ToolExecutionResultMessage) planner.fit(afterShell, config, Long.MAX_VALUE).messages().get(2)).text())
                .contains(escapedOriginal);

        var beforeShell = request.toBuilder().messages(UserMessage.from("迭代源文件"), AiMessage.from(old), full,
                AiMessage.from(write), successfulWriteReceipt(write, "sys.write", "App.java"),
                AiMessage.from(shell), desktopReceipt(shell, "sys.bash", shell.arguments(), false),
                AiMessage.from(recent), latest).build();
        assertThat(((ToolExecutionResultMessage) planner.fit(beforeShell, config, Long.MAX_VALUE).messages().get(2)).text())
                .contains("写入前版本", "new-write").doesNotContain(escapedOriginal);
    }

    @Test
    void shouldKeepReferencedReadWhenUnpairedDuplicateAppearsLater() throws Exception {
        var write = write("old-write", "sys_write", "src/App.java", ".", "确认后的代码");
        var read = fileRead("full-read", "src/App.java");
        var other = fileRead("other-file-read", "pom.xml");
        var orphan = fileRead("orphan-read", "src/App.java");
        var contents = new ObjectMapper().readTree(write.arguments()).path("content").textValue();
        var request = ChatRequest.builder().messages(UserMessage.from("迭代项目文件"), AiMessage.from(write),
                successfulWriteReceipt(write, "sys.write", "src/App.java"),
                AiMessage.from(read), verifiedRead(read, contents),
                AiMessage.from("已核对文件"), verifiedRead(orphan, contents),
                AiMessage.from(other), verifiedRead(other, contents)).build();
        var fitted = planner.fit(request, model(24_000, false), Long.MAX_VALUE);

        assertThat(((AiMessage) fitted.messages().get(1)).toolExecutionRequests().getFirst().arguments())
                .contains("full-read");
        assertThat(((ToolExecutionResultMessage) fitted.messages().get(4)).text()).contains(contents);
    }

    @Test
    void shouldKeepDesktopWriteWhenLaterReadIsUnverifiedOrSeparatedByShellMutation() throws Exception {
        var write = write("old-write", "sys_write", "src/App.java", ".", "完整源码");
        var writeResult = successfulWriteReceipt(write, "sys.write", "src/App.java");
        var samePath = fileRead("same-path", "src/App.java");
        var anotherPath = fileRead("other-path", "other/App.java");
        var contents = new ObjectMapper().readTree(write.arguments()).path("content").textValue();
        var read = verifiedRead(samePath, contents);
        var missingProof = read.toBuilder().attributes(Map.of()).build();
        var latest = fileRead("latest", "pom.xml");
        var shell = ToolExecutionRequest.builder().id("shell").name("sys_bash")
                .arguments("{\"command\":\"git status\"}").build();
        var wrongHash = completeDesktopRead(samePath, contents);
        for (var matching : List.of(verifiedRead(samePath, contents + "\n[已截断]"),
                verifiedRead(samePath, contents + "变更"), missingProof, wrongHash,
                verifiedRead(anotherPath, contents))) {
            var matchingCall = matching.id().equals(samePath.id()) ? samePath : anotherPath;
            var request = ChatRequest.builder().messages(UserMessage.from("迭代项目文件"),
                    AiMessage.from(write), writeResult, AiMessage.from(matchingCall), matching,
                    AiMessage.from(latest), completeDesktopRead(latest, "最近一次检查")).build();
            assertThat(planner.fit(request, model(24_000, false), Long.MAX_VALUE).messages().get(1))
                    .isSameAs(request.messages().get(1));
        }
        var afterShell = ChatRequest.builder().messages(UserMessage.from("迭代项目文件"),
                AiMessage.from(write), writeResult, AiMessage.from(shell),
                desktopReceipt(shell, "sys.bash", shell.arguments(), false),
                AiMessage.from(samePath), read, AiMessage.from(latest), completeDesktopRead(latest, "最近一次检查")).build();
        assertThat(planner.fit(afterShell, model(24_000, false), Long.MAX_VALUE).messages().get(1))
                .isSameAs(afterShell.messages().get(1));
    }

    @Test
    void shouldReuseMatchingHistoricalReadBeforeLaterOpaqueShell() throws Exception {
        var oldWrite = write("old-write", "sys_write", "src/App.java", ".", "完整源码");
        var matchingRead = fileRead("before-shell", "src/App.java");
        var laterRead = fileRead("after-shell", "src/App.java");
        var contents = new ObjectMapper().readTree(oldWrite.arguments()).path("content").textValue();
        var opaque = shell("opaque-shell", "git status");
        var request = ChatRequest.builder().messages(UserMessage.from("迭代项目文件"),
                AiMessage.from(oldWrite), successfulWriteReceipt(oldWrite, "sys.write", "src/App.java"),
                AiMessage.from(matchingRead), verifiedRead(matchingRead, contents),
                AiMessage.from(opaque), desktopReceipt(opaque, "sys.bash", opaque.arguments(), false),
                AiMessage.from(laterRead), verifiedRead(laterRead, contents + "文件后来变化")).build();

        var projected = planner.fit(request, model(24_000, false), Long.MAX_VALUE);

        assertThat(((AiMessage) projected.messages().get(1)).toolExecutionRequests().getFirst().arguments())
                .contains("before-shell").doesNotContain("完整源码", "after-shell");
        assertThat(((ToolExecutionResultMessage) projected.messages().get(4)).text()).contains(contents);
        assertThat(((ToolExecutionResultMessage) projected.messages().getLast()).text()).contains("文件后来变化");
        assertThat(((AiMessage) request.messages().get(1)).toolExecutionRequests()).containsExactly(oldWrite);
    }

    @Test
    void shouldKeepDesktopWriteWhenAnIntermediateWriteOrLaterRedactedReadInvalidatesReadEvidence() throws Exception {
        var write = write("old-write", "sys_write", "src/App.java", ".", "完整源码");
        var otherWrite = exactEdit("new-edit", "src/App.java", ".");
        var samePath = fileRead("old-read", "src/App.java");
        var newRead = fileRead("new-read", "src/App.java");
        var contents = new ObjectMapper().readTree(write.arguments()).path("content").textValue();
        var read = verifiedRead(samePath, contents);
        var latest = fileRead("latest", "pom.xml");
        var task = UserMessage.from("迭代项目文件");
        var archive = AiMessage.from(write);
        var writeReceipt = successfulWriteReceipt(write, "sys.write", "src/App.java");
        var latestReceipt = verifiedRead(latest, "最近一次检查");
        var afterWrite = ChatRequest.builder().messages(task, archive, writeReceipt,
                AiMessage.from(otherWrite), successfulWriteReceipt(otherWrite, "sys.edit", "src/App.java"),
                AiMessage.from(samePath), read, AiMessage.from(latest), latestReceipt).build();
        var redactedRead = verifiedRead(newRead, contents).toBuilder()
                .contents(TextContent.from("[已脱敏]")).build();
        var afterRedaction = ChatRequest.builder().messages(task, archive, writeReceipt,
                AiMessage.from(samePath), read, AiMessage.from(newRead), redactedRead,
                AiMessage.from(latest), latestReceipt).build();
        for (var request : List.of(afterWrite, afterRedaction)) {
            assertThat(planner.fit(request, model(24_000, false), Long.MAX_VALUE).messages().get(1))
                    .isSameAs(archive);
        }
    }

    @Test
    void shouldProjectOnlyRepeatedReceiptArgumentsAndKeepResultMetadata() {
        var read = read("verified-read");
        var shell = ToolExecutionRequest.builder().id("negative-cli").name("sys_bash")
                .arguments("{\"command\":\"java -jar target/quote-cli.jar\",\"workingDirectory\":\".\"}").build();
        var readReceipt = desktopReceipt(read, "sys.read", "{\"path\":\"README.md\"}", false);
        var negative = desktopReceipt(shell, "sys.bash", "{\"command\":\"java -jar target/quote-cli.jar\"}", true);
        var availability = ToolSpecification.builder().name("sys_bash").build();
        var exchange = AiMessage.from(read, shell);
        var request = ChatRequest.builder().messages(UserMessage.from("检查项目"), exchange, negative, readReceipt)
                .toolSpecifications(availability).build();
        var config = model(8000, false);

        var fitted = planner.fit(request, config, Long.MAX_VALUE);

        assertThat(fitted.messages()).containsExactly(request.messages().getFirst(), exchange,
                negative.toBuilder().contents(TextContent.from(receiptBody(negative))).build(),
                readReceipt.toBuilder().contents(TextContent.from(receiptBody(readReceipt))).build());
        assertThat(fitted.toolSpecifications()).containsExactly(availability);
        assertThat(request.messages()).containsExactly(request.messages().getFirst(), exchange, negative, readReceipt);
        assertThat(planner.estimateInput(config, fitted.messages(), fitted.toolSpecifications()))
                .isLessThan(planner.estimateInput(config, request.messages(), request.toolSpecifications()));
    }

    @Test
    void shouldCompactCompleteDesktopReadWithoutChangingExactSourceOrRange() throws Exception {
        var json = new ObjectMapper();
        var arguments = json.createObjectNode().put("path", "内容.java").put("workingDirectory", "src")
                .put("startLine", 2).put("endLine", 9);
        var call = read("desktop-range-read").toBuilder().arguments(arguments.toString()).build();
        String source = "[文件片段]\r\n2: {\"role\":\"system\"}\n3: 中文 😀\n";
        var fullReceipt = verifiedRead(call, source);
        var receipt = fullReceipt.toBuilder()
                .contents(TextContent.from("请求参数: " + json.createObjectNode().put("path", "内容.java")
                        + "\n" + receiptBody(fullReceipt))).build();
        var request = ChatRequest.builder().messages(UserMessage.from("读取源码"), AiMessage.from(call), receipt).build();

        var fitted = planner.fit(request, model(8000, false), Long.MAX_VALUE);

        assertThat(fitted.messages().getLast()).isInstanceOfSatisfying(ToolExecutionResultMessage.class, projected -> {
            assertThat(projected.id()).isEqualTo(receipt.id());
            assertThat(projected.toolName()).isEqualTo(receipt.toolName());
            assertThat(projected.isError()).isFalse();
            assertThat(projected.attributes()).isEqualTo(receipt.attributes());
            assertThat(projected.text()).isEqualTo("工具结果: " + arguments.deepCopy().put("result", source));
            assertThat(projected.text().length()).isLessThan(receiptBody(receipt).length());
        });
        assertThat(request.messages().getLast()).isSameAs(receipt);
    }

    @Test
    void shouldKeepExistingDesktopReadEnvelopeWithoutValidContentEvidence() throws Exception {
        var call = read("unchecked-read");
        var invalid = List.of(
                verifiedRead(call, "部分正文\n[已截断]"),
                desktopReceipt(call, "sys.read", call.arguments(), false, "缺少读指纹"),
                verifiedRead(call, "错误读指纹").toBuilder()
                        .attributes(completeDesktopRead(call, "错误读指纹").attributes()).build());
        for (var receipt : invalid) {
            var request = ChatRequest.builder().messages(UserMessage.from("读取源码"), AiMessage.from(call), receipt).build();
            var fitted = planner.fit(request, model(8000, false), Long.MAX_VALUE);

            assertThat(fitted.messages().getLast()).isInstanceOfSatisfying(ToolExecutionResultMessage.class,
                    projected -> assertThat(projected.text()).isEqualTo(receiptBody(receipt)));
        }
        for (String arguments : List.of("{\"path\":\"/root/Read.java\"}",
                "{\"path\":\"Read.java\",\"startLine\":9,\"endLine\":4}",
                "{\"path\":\"Read.java\",\"extra\":\"unknown\"}")) {
            var invalidCall = call.toBuilder().arguments(arguments).build();
            var receipt = verifiedRead(invalidCall, "仍保留原回执正文");
            var request = ChatRequest.builder().messages(UserMessage.from("读取源码"), AiMessage.from(invalidCall), receipt).build();

            assertThat(planner.fit(request, model(8000, false), Long.MAX_VALUE).messages().getLast())
                    .isInstanceOfSatisfying(ToolExecutionResultMessage.class,
                            projected -> assertThat(projected.text()).contains("\"callId\""));
        }
    }

    @Test
    void shouldKeepReceiptArgumentsWithoutCompleteAndTrustedEvidence() {
        var call = read("read");
        var good = desktopReceipt(call, "sys.read", "{\"path\":\"README.md\"}", false);
        var badReceipts = List.of(
                desktopReceipt(call, "sys.read", "{\"path\":\"[已脱敏]\"}", false),
                desktopReceipt(call, "sys.read", "{\"path\":\"README.md\",\"path\":\"README.md\"}", false),
                desktopReceipt(call, "sys.read", "{\"path\":\"README.md\"}...", false),
                desktopReceipt(call, "sys.write", "{\"path\":\"README.md\"}", false),
                good.toBuilder().contents(TextContent.from(good.text() + "\n[已压缩，原始长度: 4200 字符]")).build(),
                good.toBuilder().attributes(Map.of(DESKTOP_CALL_ID, "client-id")).build(),
                good.toBuilder().attributes(Map.of()).build(),
                good.toBuilder().isError(null).build()
        );
        var config = model(8000, false);
        var task = UserMessage.from("检查项目");
        for (var bad : badReceipts) {
            var request = ChatRequest.builder().messages(task, AiMessage.from(call), bad).build();
            assertThat(planner.fit(request, config, Long.MAX_VALUE).messages()).containsExactlyElementsOf(request.messages());
        }
        for (var incomplete : List.of(
                ChatRequest.builder().messages(task, good).build(),
                ChatRequest.builder().messages(task, AiMessage.from(call, read("missing")), good).build(),
                ChatRequest.builder().messages(task, AiMessage.from(call), good, good).build())) {
            assertThat(planner.fit(incomplete, config, Long.MAX_VALUE).messages())
                    .containsExactlyElementsOf(incomplete.messages());
        }
    }

    @Test
    void shouldCheckDuplicatePrefixAgainstPostProjectionWriteArguments() {
        var old = write("initial-write", "sys_write", "src/App.java", ".", "旧代码");
        var recent = write("recent-write", "sys_write", "src/App.java", ".", "更新");
        var last = write("last-write", "sys_write", "src/App.java", ".", "最终");
        var config = model(24_000, false);
        var task = UserMessage.from("迭代项目");
        var recentReceipt = writeReceipt(recent, false, "00000000-0000-4000-8000-000000000002");
        var lastReceipt = writeReceipt(last, false, "00000000-0000-4000-8000-000000000003");
        var pathOnly = desktopReceipt(old, "sys.write", "{\"path\":\"src/App.java\"}", false);
        var originalArguments = desktopReceipt(old, "sys.write", old.arguments(), false);

        var request = ChatRequest.builder().messages(task, AiMessage.from(old), pathOnly,
                AiMessage.from(recent), recentReceipt, AiMessage.from(last), lastReceipt).build();
        var projected = planner.fit(request, config, Long.MAX_VALUE);
        var projectedAi = (AiMessage) projected.messages().get(1);
        assertThat(projectedAi.toolExecutionRequests().getFirst().arguments()).contains("src/App.java")
                .contains("后续完整写入见工具调用 last-write");
        assertThat(projected.messages().get(2)).isEqualTo(pathOnly.toBuilder()
                .contents(TextContent.from(receiptBody(pathOnly))).build());

        var requestWithFullPrefix = request.toBuilder().messages(task, AiMessage.from(old), originalArguments,
                AiMessage.from(recent), recentReceipt, AiMessage.from(last), lastReceipt).build();
        assertThat(planner.fit(requestWithFullPrefix, config, Long.MAX_VALUE).messages().get(2))
                .isSameAs(originalArguments);
    }

    @Test
    void shouldProjectEarlyDesktopReadsAfterVerifiedBuildOnly() throws Exception {
        var task = UserMessage.from("迭代项目并核验");
        var system = SystemMessage.from("系统规则");
        var read = fileRead("old-read", "src/Main.java");
        var unique = fileRead("other-read", "src/Other.java").toBuilder()
                .arguments("{\"path\":\"src/Other.java\",\"startLine\":5,\"endLine\":50}").build();
        var shell = shell("opaque", "ls -l target/");
        var write = write("new-body", "sys_write", "src/Main.java", ".", "当前源码");
        var build = shell("build", "mvn clean package");
        var newestRead = fileRead("recent-read", "README.md");
        String oldContent = "此前源码".repeat(300);
        var oldRead = verifiedRead(read, oldContent);
        var repairSource = verifiedRead(unique, "修复需要的原文".repeat(150));
        var recentReceipt = verifiedRead(newestRead, "最近核验的源码".repeat(150));
        var exactWrite = AiMessage.from(write);
        var request = ChatRequest.builder().messages(system, task, AiMessage.from(read, unique), oldRead, repairSource,
                AiMessage.from(shell), desktopReceipt(shell, "sys.bash", shell.arguments(), false),
                exactWrite, successfulWriteReceipt(write, "sys.write", "src/Main.java"),
                AiMessage.from(build), verifiedBuildReceipt(build, false),
                AiMessage.from(newestRead), recentReceipt).build();
        var config = model(24_000, false);
        var baseline = planner.fit(request, config, Long.MAX_VALUE);
        var projected = planner.fit(markedDesktop(request, task), config, Long.MAX_VALUE);

        assertThat(((ToolExecutionResultMessage) baseline.messages().get(3)).text()).contains(oldContent);
        assertThat(((ToolExecutionResultMessage) projected.messages().get(3)).text())
                .contains("old-read", "src/Main.java", "fingerprint").doesNotContain(oldContent);
        assertThat(((ToolExecutionResultMessage) projected.messages().get(4)).text())
                .contains("src/Other.java", "\"startLine\":5", "\"endLine\":50", "other-read")
                .doesNotContain("修复需要的原文");
        assertThat(((ToolExecutionResultMessage) projected.messages().getLast()).text()).contains("最近核验的源码");
        assertThat(projected.messages()).contains(exactWrite).startsWith(system, task);
        assertThat(request.messages()).contains(oldRead, repairSource, recentReceipt);
        assertThat(planner.estimateInput(config, projected.messages(), List.of()))
                .isLessThan(planner.estimateInput(config, baseline.messages(), List.of()));
    }

    @Test
    void shouldProjectEarlyReadsWhenVerifiedBuildSharesRoundWithTrustedRead() throws Exception {
        var task = UserMessage.from("迭代项目并核验");
        var read = fileRead("old-read", "src/Main.java");
        var companion = fileRead("build-read", "src/Other.java");
        var build = shell("build", "mvn clean package");
        var check = fileRead("recent-read", "README.md");
        String oldContent = "此前源码".repeat(300);
        var buildReceipt = verifiedBuildReceipt(build, false);
        var companionReceipt = verifiedRead(companion, "同轮读取正文");
        var config = model(24_000, false);
        List<ChatMessage> prefix = List.of(SystemMessage.from("系统规则"), task,
                AiMessage.from(read), verifiedRead(read, oldContent));
        var request = ChatRequest.builder().messages(prefix).build();

        var eligible = new ArrayList<ChatMessage>(prefix);
        eligible.addAll(List.of(AiMessage.from(companion, build), companionReceipt, buildReceipt,
                AiMessage.from(check), verifiedRead(check, "核对说明文档")));
        var projected = planner.fit(markedDesktop(request.toBuilder().messages(eligible).build(), task),
                config, Long.MAX_VALUE);
        assertThat(((ToolExecutionResultMessage) projected.messages().get(3)).text())
                .contains("src/Main.java", "fingerprint").doesNotContain(oldContent);
        assertThat(((ToolExecutionResultMessage) projected.messages().get(5)).text()).contains("同轮读取正文");
        assertThat(((ToolExecutionResultMessage) eligible.get(3)).text()).contains(oldContent);

        var secondShell = shell("other-shell", "git status");
        var secondBuild = shell("other-build", "mvn -q clean package");
        var write = write("new-write", "sys_write", "src/Main.java", ".", "当前源码");
        var invalid = List.of(
                List.of(AiMessage.from(build, companion), buildReceipt),
                List.of(AiMessage.from(build, companion), buildReceipt, companionReceipt.toBuilder().attributes(Map.of()).build()),
                List.of(AiMessage.from(build, companion), buildReceipt,
                        desktopReceipt(companion, "sys.read", companion.arguments(), true)),
                List.of(AiMessage.from(build, secondShell), buildReceipt,
                        desktopReceipt(secondShell, "sys.bash", secondShell.arguments(), false)),
                List.of(AiMessage.from(build, secondBuild), buildReceipt, verifiedBuildReceipt(secondBuild, true)),
                List.of(AiMessage.from(build, write), buildReceipt, successfulWriteReceipt(write, "sys.write", "src/Main.java")));
        for (var exchange : invalid) {
            var messages = new ArrayList<ChatMessage>(prefix);
            messages.addAll(exchange);
            messages.addAll(List.of(AiMessage.from(check), verifiedRead(check, "核对说明文档")));
            var fitted = planner.fit(markedDesktop(request.toBuilder().messages(messages).build(), task),
                    config, Long.MAX_VALUE);
            assertThat(((ToolExecutionResultMessage) fitted.messages().get(3)).text()).contains(oldContent);
        }
    }

    @Test
    void shouldKeepHistoricalReadsAfterSteeringOrUnverifiedBuild() throws Exception {
        var task = UserMessage.from("迭代项目并核验");
        var system = SystemMessage.from("系统规则");
        var read = fileRead("old-read", "src/Main.java");
        String oldContent = "此前源码".repeat(300);
        var shell = shell("opaque", "ls -l target/");
        var write = write("new-body", "sys_write", "src/Main.java", ".", "当前源码");
        var build = shell("build", "mvn -q clean package");
        var recent = fileRead("recent", "README.md");
        var config = model(24_000, false);
        var quietBuild = verifiedBuildReceipt(build, true);
        var readBeforeAnyBuild = List.of(system, task, AiMessage.from(read), verifiedRead(read, oldContent),
                AiMessage.from(shell), desktopReceipt(shell, "sys.bash", shell.arguments(), false),
                AiMessage.from(write), successfulWriteReceipt(write, "sys.write", "src/Main.java"));
        var messages = new ArrayList<ChatMessage>(readBeforeAnyBuild);
        messages.addAll(List.of(AiMessage.from(build), quietBuild));
        var request = ChatRequest.builder().messages(messages).build();

        var green = planner.fit(markedDesktop(request, task), config, Long.MAX_VALUE);
        assertThat(((ToolExecutionResultMessage) green.messages().get(3)).text()).contains("src/Main.java")
                .doesNotContain(oldContent);
        assertThat(planner.fit(markedDesktop(request, task), null, Long.MAX_VALUE).messages())
                .containsExactlyElementsOf(request.messages());

        var wrongBuild = shell("different-build", "mvn -q verify");
        var forged = verifiedBuildReceipt(wrongBuild, true).toBuilder()
                .attributes(quietBuild.attributes()).build();
        var invalid = List.of(
                List.of(AiMessage.from(recent), desktopReceipt(recent, "sys.read", recent.arguments(), true)),
                List.of(AiMessage.from(shell), desktopReceipt(shell, "sys.bash", shell.arguments(), false)),
                List.of(AiMessage.from(write), successfulWriteReceipt(write, "sys.write", "src/Main.java")),
                List.of(AiMessage.from(recent)),
                List.of(desktopReceipt(recent, "sys.read", recent.arguments(), false)),
                List.of(AiMessage.from(wrongBuild), forged),
                List.of(UserMessage.from("再修改参数")),
                List.of(UserMessage.from(task.singleText())));
        for (var suffix : invalid) {
            var unsafe = new ArrayList<ChatMessage>(messages);
            unsafe.addAll(suffix);
            var fitted = planner.fit(markedDesktop(request.toBuilder().messages(unsafe).build(), task),
                    config, Long.MAX_VALUE);
            assertThat(((ToolExecutionResultMessage) fitted.messages().get(3)).text()).contains(oldContent);
        }
    }

    private ChatRequest markedDesktop(ChatRequest request, UserMessage task) {
        var messages = new ArrayList<>(request.messages());
        messages.set(0, AgentModelRequestPlanner.markDesktopEvidenceProjection(
                (SystemMessage) messages.getFirst(), task));
        return request.toBuilder().messages(messages).build();
    }

    private ToolExecutionRequest shell(String id, String command) {
        var args = new ObjectMapper().createObjectNode().put("command", command).toString();
        return ToolExecutionRequest.builder().id(id).name("sys_bash").arguments(args).build();
    }

    private ToolExecutionResultMessage verifiedBuildReceipt(ToolExecutionRequest call, boolean quiet) throws Exception {
        var json = new ObjectMapper();
        String command = json.readTree(call.arguments()).path("command").textValue();
        String footer = "[INFO] BUILD SUCCESS\n[INFO] Total time: 1.5 s\n[INFO] Finished at: 2026-11-24";
        var facts = json.createObjectNode().put("command", command).put("exitCode", 0);
        if (quiet) {
            facts.put("quietBuild", true);
        } else {
            facts.put("logEnd", footer);
        }
        String output = json.createObjectNode().put("exitCode", 0)
                .put("stdout", quiet ? "" : "[INFO] build output\n".repeat(150) + footer).put("stderr", "").toString();
        var receipt = desktopReceipt(call, "sys.bash", call.arguments(), false, output);
        var attributes = new HashMap<>(receipt.attributes());
        attributes.put(DESKTOP_MAVEN_MODEL_SUMMARY, facts.toString());
        return receipt.toBuilder().attributes(attributes).build();
    }

    private ToolExecutionResultMessage desktopReceipt(ToolExecutionRequest call, String toolCode,
                                                     String args, boolean error) {
        return desktopReceipt(call, toolCode, args, error, error ? "实际退出码为 1" : "工具业务结果");
    }

    private ToolExecutionResultMessage desktopReceipt(ToolExecutionRequest call, String toolCode,
                                                     String args, boolean error, String result) {
        String body = new ObjectMapper().createObjectNode().put("callId", call.id()).put("toolCode", toolCode)
                .put("success", !error).put("result", result)
                .toString();
        return ToolExecutionResultMessage.builder().id(call.id()).toolName(call.name()).isError(error)
                .text("请求参数: " + args + "\n工具结果: " + body)
                .attributes(Map.of(DESKTOP_CALL_ID, "00000000-0000-4000-8000-000000000001",
                        "compressionApplied", false, "rawChars", body.length(), "compressedChars", body.length()))
                .build();
    }

    private ToolExecutionResultMessage successfulWriteReceipt(ToolExecutionRequest call, String toolCode, String path) {
        var arguments = new ObjectMapper().createObjectNode().put("path", path);
        return desktopReceipt(call, toolCode, arguments.toString(), false, "已保存 " + path);
    }

    private ToolExecutionResultMessage verifiedRead(ToolExecutionRequest call, String content) throws Exception {
        var receipt = completeDesktopRead(call, content);
        var json = new ObjectMapper();
        String evidence = json.createArrayNode().add(json.readTree(call.arguments())).add(content).toString();
        var attributes = new HashMap<>(receipt.attributes());
        attributes.put(DESKTOP_READ_EVIDENCE_HASH, HashUtils.sha256Hex(evidence.getBytes(StandardCharsets.UTF_8)));
        return receipt.toBuilder().attributes(attributes).build();
    }

    private String receiptBody(ToolExecutionResultMessage receipt) {
        return receipt.text().substring(receipt.text().indexOf('\n') + 1);
    }

    private ToolExecutionRequest write(String id, String tool, String path, String directory, String content) {
        var args = new ObjectMapper().createObjectNode().put("path", path).put("content", content.repeat(320));
        if (directory != null) args.put("workingDirectory", directory);
        return ToolExecutionRequest.builder().id(id).name(tool).arguments(args.toString()).build();
    }

    private ToolExecutionResultMessage writeReceipt(ToolExecutionRequest request, boolean error, String desktopId) {
        return ToolExecutionResultMessage.builder().id(request.id()).toolName(request.name()).isError(error)
                .text(error ? "保存失败" : "已保存")
                .attributes(desktopId == null ? Map.of() : Map.of(DESKTOP_CALL_ID, desktopId)).build();
    }

    private ToolExecutionRequest read(String id) {
        return ToolExecutionRequest.builder().id(id).name("sys_read").arguments("{\"path\":\"README.md\"}").build();
    }

    private ToolExecutionRequest fileRead(String id, String path) {
        return ToolExecutionRequest.builder().id(id).name("sys_read")
                .arguments(new ObjectMapper().createObjectNode().put("path", path).toString()).build();
    }

    private ToolExecutionRequest exactEdit(String id, String path, String directory) {
        var args = new ObjectMapper().createObjectNode().put("path", path).put("workingDirectory", directory)
                .put("oldText", "旧源码片段".repeat(80)).put("newText", "新源码片段".repeat(80));
        return ToolExecutionRequest.builder().id(id).name("sys_edit").arguments(args.toString()).build();
    }

    private String taskSection(UserMessage authored) {
        return "\n\n---\n\n## 当前任务上下文\n" + authored.singleText();
    }

    private SystemMessage markTaskContext(SystemMessage canonical, UserMessage authored, int start) {
        var range = new ContextInjector.TaskContextRange(start, start + taskSection(authored).length(),
                authored.singleText());
        return AgentModelRequestPlanner.markTaskContext(canonical, authored, range);
    }

    private ToolExecutionResultMessage result(ToolExecutionRequest request, String text, String hash, boolean error) {
        return ToolExecutionResultMessage.builder().id(request.id()).toolName(request.name()).text(text).isError(error)
                .attributes(hash != null ? Map.of(DESKTOP_READ_EVIDENCE_HASH, hash) : Map.of()).build();
    }

    private AiModelConfig model(int window, boolean vision) {
        return AiModelConfig.builder().modelId("custom-fixture").maxTokens(700)
                .contextWindowTokens(window).multimodal(vision).build();
    }
}
