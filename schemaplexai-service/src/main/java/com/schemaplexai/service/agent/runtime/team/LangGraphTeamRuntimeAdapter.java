package com.schemaplexai.service.agent.runtime.team;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;

import java.util.Map;
import java.util.Optional;

/**
 * LangGraph4j Team Agent 运行时适配器，集中封装 checkpoint、interrupt、resume 等框架调用。
 */
public class LangGraphTeamRuntimeAdapter {

    public CompileConfig buildCompileConfig(PostgresSaver saver) {
        return CompileConfig.builder()
                .checkpointSaver(saver)
                .interruptBefore(TeamGraphConstants.NODE_AWAIT_INPUT)
                .releaseThread(false)
                .build();
    }

    public Optional<TeamGraphState> resume(CompiledGraph<TeamGraphState> compiledGraph,
                                           RunnableConfig runnableConfig,
                                           String userInput,
                                           Map<String, Object> userOptions) throws Exception {
        RunnableConfig updatedConfig = compiledGraph.updateState(
                runnableConfig,
                Map.of(
                        TeamGraphConstants.STATE_USER_INPUT, userInput,
                        TeamGraphConstants.STATE_USER_OPTIONS, userOptions == null ? Map.of() : userOptions
                )
        );
        return compiledGraph.invoke(GraphInput.resume(), updatedConfig);
    }

    public Optional<TeamGraphState> lastStateOf(CompiledGraph<TeamGraphState> compiledGraph,
                                               RunnableConfig runnableConfig) {
        return compiledGraph.lastStateOf(runnableConfig).map(snapshot -> snapshot.state());
    }

    public void releaseCheckpoint(CompileConfig compileConfig, RunnableConfig runnableConfig) throws Exception {
        if (compileConfig.checkpointSaver().isPresent()) {
            compileConfig.checkpointSaver().get().release(runnableConfig);
        }
    }
}
