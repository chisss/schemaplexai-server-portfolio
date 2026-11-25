package com.schemaplexai.service.agent.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.enums.ToolIoTypeEnum;
import com.schemaplexai.common.model.ToolResult;
import com.schemaplexai.service.agent.tool.langchain4j.AgentToolSessionFactory.RoundToolContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolServiceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 并行工具执行顺序契约（审计 M3）：相邻连续 READ 段内并行，
 * 写工具与各段之间保持模型请求原始顺序，不破坏 write→read 验证类依赖。
 */
class AgentLoopToolHandlerParallelOrderTest {

    private final AgentLoopToolHandler handler = new AgentLoopToolHandler(new ObjectMapper());
    private final ExecutorService parallelExecutor = Executors.newFixedThreadPool(4);

    @AfterEach
    void closeExecutor() {
        parallelExecutor.close();
    }

    private ToolExecutionRequest request(String id, String name) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build();
    }

    private ToolExecutor executor(String resultText) {
        ToolExecutor toolExecutor = mock(ToolExecutor.class);
        when(toolExecutor.executeWithContext(any(), any()))
                .thenReturn(ToolExecutionResult.builder()
                        .isError(false)
                        .resultText(resultText)
                        .build());
        return toolExecutor;
    }

    private RoundToolContext roundCtx(Map<String, ToolExecutor> executors, Map<String, ToolIoTypeEnum> ioTypes) {
        List<ToolSpecification> specifications = executors.keySet().stream()
                .map(name -> ToolSpecification.builder().name(name).description("测试工具").build())
                .toList();
        ToolServiceContext toolServiceContext = ToolServiceContext.builder()
                .availableTools(specifications)
                .toolSpecifications(specifications)
                .effectiveTools(specifications)
                .toolExecutors(executors)
                .build();
        return new RoundToolContext(toolServiceContext, null, ioTypes);
    }

    @Test
    void shouldExecuteWriteBeforeFollowingRead() {
        ToolExecutor writeExecutor = executor("written");
        ToolExecutor readExecutor = executor("read");
        RoundToolContext ctx = roundCtx(
                Map.of("write_tool", writeExecutor, "read_tool", readExecutor),
                Map.of("write_tool", ToolIoTypeEnum.WRITE, "read_tool", ToolIoTypeEnum.READ));

        // 同轮请求：先写后读（write→read 验证类调用，读不得提前到写之前）
        List<ToolExecutionRequest> requests = List.of(
                request("1", "write_tool"),
                request("2", "read_tool"));

        handler.executeParallel(ctx, requests, null, parallelExecutor, 4, ctx.ioTypeIndex());

        InOrder order = inOrder(writeExecutor, readExecutor);
        order.verify(writeExecutor).executeWithContext(any(), any());
        order.verify(readExecutor).executeWithContext(any(), any());
    }

    @Test
    void shouldKeepReadBeforeWriteBeforeReadOrderAcrossSegments() {
        ToolExecutor read1 = executor("r1");
        ToolExecutor write = executor("w");
        ToolExecutor read2 = executor("r2");
        RoundToolContext ctx = roundCtx(
                Map.of("read_1", read1, "write_tool", write, "read_2", read2),
                Map.of("read_1", ToolIoTypeEnum.READ, "write_tool", ToolIoTypeEnum.WRITE, "read_2", ToolIoTypeEnum.READ));

        List<ToolExecutionRequest> requests = List.of(
                request("1", "read_1"),
                request("2", "write_tool"),
                request("3", "read_2"));

        handler.executeParallel(ctx, requests, null, parallelExecutor, 4, ctx.ioTypeIndex());

        // read_1 段在前、write 居中、read_2 段在后：跨段顺序保持请求原序
        InOrder order = inOrder(read1, write, read2);
        order.verify(read1).executeWithContext(any(), any());
        order.verify(write).executeWithContext(any(), any());
        order.verify(read2).executeWithContext(any(), any());
    }

    @Test
    void shouldKeepResultsAtOriginalPositions() {
        ToolExecutor r1 = executor("r1");
        ToolExecutor r2 = executor("r2");
        RoundToolContext ctx = roundCtx(
                Map.of("read_1", r1, "read_2", r2),
                Map.of("read_1", ToolIoTypeEnum.READ, "read_2", ToolIoTypeEnum.READ));

        List<ToolExecutionRequest> requests = List.of(
                request("1", "read_1"),
                request("2", "read_2"));

        List<AgentLoopToolHandler.ToolExecutionOutcome> outcomes =
                handler.executeParallel(ctx, requests, null, parallelExecutor, 4, ctx.ioTypeIndex());

        assertThat(outcomes).hasSize(2);
        assertThat(String.valueOf(outcomes.get(0).toolResult().getResult())).contains("r1");
        assertThat(String.valueOf(outcomes.get(1).toolResult().getResult())).contains("r2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"block", "pause"})
    void shouldSkipFollowingWritesAfterReadBatchInterrupt(String action) {
        ToolExecutor interrupted = interruptingExecutor("1", "read_1", action);
        ToolExecutor write = executor("write");
        ToolExecutor laterRead = executor("read");
        RoundToolContext ctx = roundCtx(Map.of("read_1", interrupted, "write", write, "read_2", laterRead),
                Map.of("read_1", ToolIoTypeEnum.READ, "write", ToolIoTypeEnum.WRITE, "read_2", ToolIoTypeEnum.READ));

        var results = handler.executeParallel(ctx,
                List.of(request("1", "read_1"), request("2", "write"), request("3", "read_2")),
                null, parallelExecutor, 1, ctx.ioTypeIndex());

        assertThat(results).extracting(outcome -> outcome.resultMessage().id()).containsExactly("1", "2", "3");
        assertThat(results).extracting(AgentLoopToolHandler.ToolExecutionOutcome::wasDispatched)
                .containsExactly(true, false, false);
        assertThat(results.getFirst().toolResult().getControlAction()).isEqualTo(action);
        verifyNoInteractions(write, laterRead);
    }

    @Test
    void shouldSkipFollowingWritesInSequentialMode() {
        ToolExecutor interrupted = interruptingExecutor("1", "first", "pause");
        ToolExecutor write = executor("write");
        RoundToolContext ctx = roundCtx(Map.of("first", interrupted, "write", write),
                Map.of("first", ToolIoTypeEnum.WRITE, "write", ToolIoTypeEnum.WRITE));

        var results = handler.executeBatch(ctx, List.of(request("1", "first"), request("2", "write")),
                null, null, 4, ctx.ioTypeIndex(), () -> true);

        assertThat(results.getLast().resultMessage().attributes()).containsEntry("dispatchStatus", "SKIPPED");
        verifyNoInteractions(write);
    }

    @Test
    void shouldKeepAlreadySubmittedCallsWhenLaterSubmissionIsRejected() {
        ToolExecutor first = executor("first");
        ToolExecutor rejected = executor("rejected");
        ToolExecutor last = executor("last");
        RoundToolContext ctx = roundCtx(Map.of("first", first, "rejected", rejected, "last", last),
                Map.of("first", ToolIoTypeEnum.READ, "rejected", ToolIoTypeEnum.READ, "last", ToolIoTypeEnum.READ));
        AtomicInteger submissions = new AtomicInteger();
        Executor partialExecutor = runnable -> {
            if (submissions.incrementAndGet() == 2) {
                throw new RejectedExecutionException();
            }
            parallelExecutor.execute(runnable);
        };

        var results = handler.executeParallel(ctx,
                List.of(request("1", "first"), request("2", "rejected"), request("3", "last")),
                null, partialExecutor, 4, ctx.ioTypeIndex());

        assertThat(results).extracting(outcome -> outcome.resultMessage().id()).containsExactly("1", "2", "3");
        assertThat(results.get(1).resultMessage().attributes()).containsEntry("dispatchStatus", "BUSY");
        verify(first).executeWithContext(any(), any());
        verify(last).executeWithContext(any(), any());
        verifyNoInteractions(rejected);
    }

    @Test
    void shouldCheckCancellationInsideQueuedReadTask() {
        ToolExecutor read = executor("read");
        RoundToolContext ctx = roundCtx(Map.of("read", read), Map.of("read", ToolIoTypeEnum.READ));
        AtomicBoolean running = new AtomicBoolean(true);
        Executor queued = task -> {
            running.set(false);
            task.run();
        };

        var results = handler.executeBatch(ctx, List.of(request("1", "read")), null, queued, 1,
                ctx.ioTypeIndex(), running::get);

        assertThat(results.getFirst().wasDispatched()).isFalse();
        verifyNoInteractions(read);
    }

    private ToolExecutor interruptingExecutor(String id, String name, String action) {
        ToolExecutor tool = mock(ToolExecutor.class);
        when(tool.executeWithContext(any(), any())).thenReturn(ToolExecutionResult.builder()
                .isError(true)
                .resultText("测试安全中断")
                .result(ToolResult.builder().callId(id).toolCode(name).controlAction(action).build())
                .build());
        return tool;
    }
}
