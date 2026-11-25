package com.schemaplexai.service.agent.tool.langchain4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schemaplexai.common.model.ToolResult;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.service.agent.execution.SandboxPolicy;
import com.schemaplexai.service.agent.tool.executor.ToolExecutor;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import com.schemaplexai.service.desktop.DesktopToolExecutor;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static com.schemaplexai.service.agent.tool.langchain4j.LangChain4jBindingToolExecutor.DESKTOP_MAVEN_MODEL_SUMMARY;
import static org.assertj.core.api.Assertions.assertThat;
import static com.schemaplexai.service.agent.tool.langchain4j.LangChain4jBindingToolExecutor.DESKTOP_READ_EVIDENCE_HASH;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LangChain4jBindingToolExecutorTest {

    @Test
    void shouldExecuteUsingActualBindingToolCodeWhenModelNameIsNormalized() {
        ToolExecutor delegate = mock(ToolExecutor.class);
        AgentToolBinding binding = new AgentToolBinding();
        binding.setToolCode("sys.read");
        SandboxPolicy sandboxPolicy = SandboxPolicy.builder().build();
        when(delegate.execute(eq("tenant-1"), eq("agent-1"), eq(binding), org.mockito.ArgumentMatchers.any(ToolCall.class), eq(sandboxPolicy)))
                .thenReturn(ToolResult.builder()
                        .toolCode("sys.read")
                        .success(true)
                        .build());

        LangChain4jBindingToolExecutor executor = new LangChain4jBindingToolExecutor(
                new ObjectMapper(),
                delegate,
                "tenant-1",
                "agent-1",
                binding,
                sandboxPolicy
        );

        var result = executor.executeWithContext(ToolExecutionRequest.builder()
                .id("call-1")
                .name("sys_read")
                .arguments("{\"path\":\"README.md\"}")
                .build(), null);

        ArgumentCaptor<ToolCall> toolCallCaptor = ArgumentCaptor.forClass(ToolCall.class);
        verify(delegate).execute(eq("tenant-1"), eq("agent-1"), eq(binding), toolCallCaptor.capture(), eq(sandboxPolicy));
        assertThat(toolCallCaptor.getValue().getToolCode()).isEqualTo("sys.read");
        assertThat(result.attributes()).isEmpty();
    }

    @Test
    void shouldFingerprintDesktopReadArgumentsAndContentsIndependentlyOfCallId() {
        var mapper = new ObjectMapper();
        var desktop = mock(DesktopToolExecutor.class);
        var binding = new AgentToolBinding();
        binding.setToolCode("sys.read");
        var policy = SandboxPolicy.builder().build();
        var contents = mapper.createObjectNode().put("content", "测试结果");
        when(desktop.execute(eq("tenant"), eq("agent"), eq(binding), any(ToolCall.class), eq(policy)))
                .thenAnswer(invocation -> {
                    ToolCall call = invocation.getArgument(3);
                    return ToolResult.builder().callId(call.getCallId()).success(true).result(contents).build();
                });
        var executor = new LangChain4jBindingToolExecutor(mapper, desktop, "tenant", "agent", binding, policy);
        var first = executor.executeWithContext(read("call-1", "README.md"), null);
        var repeated = executor.executeWithContext(read("call-2", "README.md"), null);
        var otherPath = executor.executeWithContext(read("call-3", "OTHER.md"), null);
        contents.put("content", "更新后的结果");
        var changed = executor.executeWithContext(read("call-4", "README.md"), null);

        assertThat(first.attributes()).containsKey(DESKTOP_READ_EVIDENCE_HASH).isEqualTo(repeated.attributes());
        assertThat(otherPath.attributes()).isNotEqualTo(first.attributes());
        assertThat(changed.attributes()).isNotEqualTo(first.attributes());
    }

    @Test
    void shouldAttachStructuredMavenSummaryWithoutChangingDesktopReceipt() throws Exception {
        var mapper = new ObjectMapper();
        var delegate = mock(DesktopToolExecutor.class);
        String log = mavenLog("[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 1")
                .replace("Skipped: 0, Time elapsed:", "Skipped: 1, Time elapsed:")
                .replace("[INFO] BUILD SUCCESS", "\u001b[34m[INFO]\u001b[m \u001b[32mBUILD SUCCESS\u001b[m");
        var receipt = mavenReceipt(mapper, true, 0, log,
                "NOTE: Picked up JDK_JAVA_OPTIONS: -Duser.home=\"/private/tmp/isolated-home\"\n");
        var result = mavenResult(mapper, delegate, "mvn -B --no-transfer-progress package", receipt);

        var summary = mapper.readTree((String) result.attributes().get(DESKTOP_MAVEN_MODEL_SUMMARY));
        assertThat(summary.path("exitCode").intValue()).isZero();
        assertThat(summary.path("tests").intValue()).isEqualTo(12);
        assertThat(summary.path("failures").intValue()).isZero();
        assertThat(summary.path("errors").intValue()).isZero();
        assertThat(summary.path("skipped").intValue()).isEqualTo(1);
        assertThat(result.attributes()).doesNotContainKey("desktopMavenSourceFailure");
        assertThat(summary.path("logStart").asText()).contains("Scanning for projects");
        assertThat(summary.path("logEnd").asText()).contains("target/quote-cli.jar", "BUILD SUCCESS", "Total time", "Finished at");
        assertThat(result.result()).isSameAs(receipt);
        assertThat(mapper.readTree(result.resultText())).isEqualTo(mapper.valueToTree(receipt));
        assertThat(mapper.readTree(receipt.getResult().textValue()).path("stdout").asText()).isEqualTo(log);
    }

    @Test
    void shouldRecordQuietMavenExitWithoutClaimingTestResults() throws Exception {
        var mapper = new ObjectMapper();
        var desktop = mock(DesktopToolExecutor.class);
        var receipt = mavenReceipt(mapper, true, 0, "",
                "NOTE: Picked up JDK_JAVA_OPTIONS: -Duser.home=/private/tmp/isolated-home\n");
        assertThat(mavenResult(mapper, desktop, "mvn -q clean package", receipt).attributes())
                .doesNotContainKey(DESKTOP_MAVEN_MODEL_SUMMARY);
        receipt.setDesktopCallId(UUID.randomUUID());

        var result = mavenResult(mapper, desktop, "mvn -q clean package", receipt);
        var summary = mapper.readTree((String) result.attributes().get(DESKTOP_MAVEN_MODEL_SUMMARY));

        assertThat(summary.path("command").textValue()).isEqualTo("mvn -q clean package");
        assertThat(summary.path("exitCode").intValue()).isZero();
        assertThat(summary.path("quietBuild").booleanValue()).isTrue();
        assertThat(summary.path("testSummary").textValue()).contains("未观测");
        assertThat(summary.has("tests")).isFalse();
        assertThat(result.result()).isSameAs(receipt);
        receipt.setCallId("different-call");
        assertThat(mavenResult(mapper, desktop, "mvn -q clean package", receipt).attributes())
                .doesNotContainKey(DESKTOP_MAVEN_MODEL_SUMMARY);
    }

    @Test
    void shouldAcceptMavenSeparatorAfterBuildSuccess() throws Exception {
        var mapper = new ObjectMapper();
        String log = mavenLog("[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0")
                .replace("[INFO] BUILD SUCCESS\n", "[INFO] BUILD SUCCESS\n"
                        + "[INFO] ------------------------------------------------------------------------\n");
        var receipt = mavenReceipt(mapper, true, 0, log, "");
        var desktop = mock(DesktopToolExecutor.class);

        var result = mavenResult(mapper, desktop, "mvn clean package", receipt);

        assertThat(result.attributes()).containsKey(DESKTOP_MAVEN_MODEL_SUMMARY);
        var summary = mapper.readTree((String) result.attributes().get(DESKTOP_MAVEN_MODEL_SUMMARY));
        assertThat(summary.path("tests").asInt()).isEqualTo(12);
        assertThat(summary.path("logEnd").asText()).contains("BUILD SUCCESS", "Total time", "Finished at");
        assertThat(result.result()).isSameAs(receipt);
        assertThat(mavenResult(mapper, desktop, "mvn clean package",
                mavenReceipt(mapper, true, 0, log.replace("[INFO] BUILD SUCCESS\n",
                        "[INFO] BUILD SUCCESS\n[ERROR] 未完成退出清理\n"), "")).attributes()).isEmpty();
    }

    @Test
    void shouldKeepRawResultForOtherCommandsFailuresAndIncompleteMavenEvidence() {
        var mapper = new ObjectMapper();
        String tests = "[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0";
        String log = mavenLog(tests);
        var receipt = mavenReceipt(mapper, true, 0, log, "");
        assertThat(mavenResult(mapper, mock(ToolExecutor.class), "mvn -B test", receipt).attributes()).isEmpty();
        assertThat(mavenResult(mapper, mock(DesktopToolExecutor.class), "mvn test && echo done", receipt)
                .attributes()).isEmpty();
        assertThat(mavenResult(mapper, mock(DesktopToolExecutor.class), "mvn test -DskipTests", receipt)
                .attributes()).isEmpty();

        var delegate = mock(DesktopToolExecutor.class);
        for (ToolResult excluded : new ToolResult[]{
                mavenReceipt(mapper, false, 0, log, ""),
                mavenReceipt(mapper, true, 1, log, ""),
                mavenReceipt(mapper, true, 0, log, "诊断内容需要保留"),
                mavenReceipt(mapper, true, 0, mavenLog("无测试摘要"), ""),
                mavenReceipt(mapper, true, 0, mavenLog(tests + "\n" + tests), ""),
                mavenReceipt(mapper, true, 0, mavenLog(tests.replace("run: 12", "run: 13")), ""),
                mavenReceipt(mapper, true, 0, mavenLog(tests.replace("Skipped: 0", "Skipped: 12")), ""),
                mavenReceipt(mapper, true, 0, mavenLog(tests.replace("Failures: 0", "Failures: 1")), ""),
                mavenReceipt(mapper, true, 0, mavenLog("[WARNING] 构建告警\n" + tests), ""),
                mavenReceipt(mapper, true, 0, log.substring(0, log.length() - 18), "")
        }) {
            var result = mavenResult(mapper, delegate, "mvn -B test", excluded);
            assertThat(result.attributes()).isEmpty();
            assertThat(result.result()).isSameAs(excluded);
        }
    }

    @Test
    void shouldProvideColdCacheMavenCompilerErrorsWithoutChangingFailureReceipt() throws Exception {
        var mapper = new ObjectMapper();
        var desktop = mock(DesktopToolExecutor.class);
        String stdout = "Downloading from central: maven-compiler-plugin\n".repeat(145)
                + "\n[中间输出已截断]\n\u001b[1;31m[ERROR]\u001b[m COMPILATION ERROR\n"
                + "[ERROR] src/test/java/QuoteTest.java:[24,17] cannot find symbol\n"
                + "[ERROR]   symbol: method total(java.lang.String,int)\n"
                + "[INFO] BUILD FAILURE\n";
        var output = mapper.createObjectNode().put("exitCode", 1).put("stdout", stdout)
                .put("stderr", "NOTE: Picked up JDK_JAVA_OPTIONS: -Duser.home=/tmp/maven\n编译器诊断")
                .put("stdoutTruncated", true).put("stderrTruncated", false);
        var receipt = ToolResult.builder().callId("maven-1").toolCode("sys.bash").success(false)
                .desktopCallId(UUID.randomUUID()).errorMessage("命令退出码为 1")
                .result(mapper.getNodeFactory().textNode(output.toString())).build();

        var result = mavenResult(mapper, desktop, "mvn clean package", receipt);

        var summary = mapper.readTree((String) result.attributes().get(DESKTOP_MAVEN_MODEL_SUMMARY));
        assertThat(summary.path("exitCode").intValue()).isEqualTo(1);
        assertThat(summary.path("errors").textValue()).contains("QuoteTest.java:[24,17]", "total(java.lang.String,int)")
                .doesNotContain("Downloading", "\u001b");
        assertThat(result.attributes()).containsEntry("desktopMavenSourceFailure", true);
        assertThat(summary.path("stderr").textValue()).isEqualTo("编译器诊断");
        assertThat(summary.has("tests")).isFalse();
        assertThat(result.isError()).isTrue();
        assertThat(result.result()).isSameAs(receipt);
        assertThat(result.resultText()).contains("Downloading from central");
        assertThat(mavenResult(mapper, mock(ToolExecutor.class), "mvn clean package", receipt)
                .attributes()).doesNotContainKey(DESKTOP_MAVEN_MODEL_SUMMARY);
        assertThat(mavenResult(mapper, desktop, "mvn clean package && echo done", receipt)
                .attributes()).doesNotContainKey(DESKTOP_MAVEN_MODEL_SUMMARY);
        receipt.setCallId("different-call");
        assertThat(mavenResult(mapper, desktop, "mvn clean package", receipt)
                .attributes()).doesNotContainKeys(DESKTOP_MAVEN_MODEL_SUMMARY, "desktopMavenSourceFailure");
        receipt.setCallId("maven-1");
        output.put("stdout", "[ERROR] Could not resolve dependencies");
        receipt.setResult(mapper.getNodeFactory().textNode(output.toString()));
        assertThat(mavenResult(mapper, desktop, "mvn clean package", receipt)
                .attributes()).containsKey(DESKTOP_MAVEN_MODEL_SUMMARY).doesNotContainKey("desktopMavenSourceFailure");
        output.put("stdout", stdout);
        output.put("stdoutTruncated", "true");
        receipt.setResult(mapper.getNodeFactory().textNode(output.toString()));
        assertThat(mavenResult(mapper, desktop, "mvn clean package", receipt)
                .attributes()).doesNotContainKey(DESKTOP_MAVEN_MODEL_SUMMARY);
    }

    @Test
    void shouldReportExitCodeWithoutInferringTestsWhenDeviceOutputReachesCaptureLimit() throws Exception {
        var mapper = new ObjectMapper();
        var desktop = mock(DesktopToolExecutor.class);
        String stdout = ("[INFO] Scanning for projects...\n[INFO] Building quote-cli\n"
                + "Downloading from central: https://repo.maven.apache.org/maven2/\n".repeat(200)).substring(0, 8_000);
        var receipt = mavenReceipt(mapper, true, 0, stdout, "");
        receipt.setDesktopCallId(UUID.randomUUID());

        var result = mavenResult(mapper, desktop, "mvn clean package", receipt);

        var summary = mapper.readTree((String) result.attributes().get(DESKTOP_MAVEN_MODEL_SUMMARY));
        assertThat(summary.path("exitCode").intValue()).isZero();
        assertThat(summary.path("logAtCaptureLimit").booleanValue()).isTrue();
        assertThat(summary.has("tests")).isFalse();
        assertThat(result.result()).isSameAs(receipt);
        assertThat(mavenResult(mapper, desktop, "mvn clean package", mavenReceipt(mapper, true, 0, stdout, ""))
                .attributes()).doesNotContainKey(DESKTOP_MAVEN_MODEL_SUMMARY);
    }

    @Test
    void shouldSummarizeTruncatedMavenPackageWithRealBuildSuccessAndMissingTestCounts() throws Exception {
        var mapper = new ObjectMapper();
        var desktop = mock(DesktopToolExecutor.class);
        String stdout = "[INFO] Scanning for projects...\n" + "Downloading dependencies\n".repeat(55)
                + "\n[中间输出已截断]\n" + "Downloaded library\n".repeat(210) + """
                [INFO] Building jar: target/quote-cli.jar
                [INFO] ------------------------------------------------------------------------
                [INFO] BUILD SUCCESS
                [INFO] ------------------------------------------------------------------------
                [INFO] Total time: 9.075 s
                [INFO] Finished at: 2026-11-24T11:53:36+08:00
                [INFO] ------------------------------------------------------------------------
                """;
        var receipt = mavenReceipt(mapper, true, 0, stdout, "NOTE: Picked up JDK_JAVA_OPTIONS: -Duser.home=/tmp/example\n");
        receipt.setDesktopCallId(UUID.randomUUID());
        var output = (ObjectNode) mapper.readTree(receipt.getResult().textValue());
        output.put("stdoutTruncated", true);
        receipt.setResult(mapper.getNodeFactory().textNode(output.toString()));

        var result = mavenResult(mapper, desktop, "mvn clean package", receipt);

        var summary = mapper.readTree((String) result.attributes().get(DESKTOP_MAVEN_MODEL_SUMMARY));
        assertThat(summary.path("logAtCaptureLimit").booleanValue()).isTrue();
        assertThat(summary.path("logEnd").asText()).contains("BUILD SUCCESS");
        assertThat(summary.path("testSummary").asText()).contains("未核验测试数量");
        assertThat(summary.has("tests")).isFalse();
        assertThat(result.result()).isSameAs(receipt);
        output.put("stdout", stdout.replace("Downloaded library", "[WARNING] 构建告警"));
        receipt.setResult(mapper.getNodeFactory().textNode(output.toString()));
        assertThat(mavenResult(mapper, desktop, "mvn clean package", receipt).attributes())
                .doesNotContainKey(DESKTOP_MAVEN_MODEL_SUMMARY);
    }

    @Test
    void shouldSummarizeCompleteMavenFailureWhenDesktopOmitsTruncationFlag() throws Exception {
        var mapper = new ObjectMapper();
        var desktop = mock(DesktopToolExecutor.class);
        String stdout = "[INFO] Scanning for projects...\n"
                + "[ERROR] COMPILATION ERROR\n"
                + "[ERROR] QuoteCalculatorTest.java:[130,68] no suitable method found for total(java.lang.String,int)\n"
                + "[ERROR]   method QuoteCalculator.total(java.math.BigDecimal,int) is not applicable\n"
                + "[ERROR]   method QuoteCalculator.total(java.lang.String) is not applicable\n"
                + "[ERROR] Maven compiler context\n"
                + "[ERROR] More Maven compiler context\n"
                + "[ERROR] Final Maven compiler context\n"
                + "[ERROR] QuoteCalculatorTest.java:[136,68] no suitable method found for total(java.lang.String,int)\n"
                + "[ERROR] QuoteCalculatorTest.java:[142,68] no suitable method found for total(java.lang.String,int)\n";
        var output = mapper.createObjectNode().put("exitCode", 1).put("stdout", stdout).put("stderr", "");
        var receipt = ToolResult.builder().callId("maven-1").toolCode("sys.bash").success(false)
                .desktopCallId(UUID.randomUUID()).errorMessage("命令退出码为 1")
                .result(mapper.getNodeFactory().textNode(output.toString())).build();

        var result = mavenResult(mapper, desktop, "mvn clean package", receipt);

        var summary = mapper.readTree((String) result.attributes().get(DESKTOP_MAVEN_MODEL_SUMMARY));
        assertThat(summary.path("logAtCaptureLimit").booleanValue()).isFalse();
        assertThat(summary.path("errors").textValue())
                .contains("no suitable method", "BigDecimal", "String", "[130,68]", "[136,68]", "[142,68]");
        assertThat(result.isError()).isTrue();
    }

    private String mavenLog(String tests) {
        return "[INFO] Scanning for projects...\n[INFO] Building quote-cli 1.0\n"
                + "[INFO] 工作区详细构建输出\n".repeat(100)
                + "[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.040 s -- in QuoteTest\n"
                + tests + "\n"
                + """
                [INFO] Building jar: target/quote-cli.jar
                [INFO] ------------------------------------------------------------------------
                [INFO] BUILD SUCCESS
                [INFO] Total time:  1.801 s
                [INFO] Finished at: 2026-11-22T15:31:53+08:00
                [INFO] ------------------------------------------------------------------------
                """;
    }

    private ToolResult mavenReceipt(ObjectMapper mapper, boolean success, int exitCode, String stdout, String stderr) {
        var output = mapper.createObjectNode().put("exitCode", exitCode).put("stdout", stdout).put("stderr", stderr);
        return ToolResult.builder().callId("maven-1").toolCode("sys.bash").success(success)
                .result(mapper.getNodeFactory().textNode(output.toString())).build();
    }

    private ToolExecutionResult mavenResult(
            ObjectMapper mapper, ToolExecutor delegate, String command, ToolResult receipt) {
        var binding = new AgentToolBinding();
        binding.setToolCode("sys.bash");
        var policy = SandboxPolicy.builder().build();
        when(delegate.execute(eq("tenant"), eq("agent"), eq(binding), any(ToolCall.class), eq(policy)))
                .thenReturn(receipt);
        var executor = new LangChain4jBindingToolExecutor(mapper, delegate, "tenant", "agent", binding, policy);
        var request = ToolExecutionRequest.builder().id("maven-1").name("sys_bash")
                .arguments(mapper.createObjectNode().put("command", command).toString()).build();
        return executor.executeWithContext(request, null);
    }

    private ToolExecutionRequest read(String callId, String path) {
        return ToolExecutionRequest.builder().id(callId).name("sys_read")
                .arguments("{\"path\":\"" + path + "\"}").build();
    }
}
