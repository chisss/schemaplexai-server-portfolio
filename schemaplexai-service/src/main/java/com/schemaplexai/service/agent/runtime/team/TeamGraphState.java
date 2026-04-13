package com.schemaplexai.service.agent.runtime.team;

import org.bsc.langgraph4j.state.AgentState;

import java.util.List;
import java.util.Map;

/**
 * Team Graph 状态
 */
public class TeamGraphState extends AgentState {

    public TeamGraphState(Map<String, Object> initData) {
        super(initData);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> memberResults() {
        return value(TeamGraphConstants.STATE_MEMBER_RESULTS)
                .map(v -> (List<Map<String, Object>>) v)
                .orElse(List.of());
    }

    public String finalStatus() {
        return value(TeamGraphConstants.STATE_FINAL_STATUS, (String) null);
    }

    public String finalOutput() {
        return value(TeamGraphConstants.STATE_FINAL_OUTPUT, (String) null);
    }

    public String finalError() {
        return value(TeamGraphConstants.STATE_FINAL_ERROR, (String) null);
    }

    public int retryCount() {
        return value(TeamGraphConstants.STATE_RETRY_COUNT, 0);
    }

    public String userInput() {
        return value(TeamGraphConstants.STATE_USER_INPUT, (String) null);
    }

    public String qualityGateDecision() {
        return value(TeamGraphConstants.STATE_QUALITY_GATE_DECISION, (String) null);
    }

    public String qualityGateSummary() {
        return value(TeamGraphConstants.STATE_QUALITY_GATE_SUMMARY, (String) null);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> userOptions() {
        return value(TeamGraphConstants.STATE_USER_OPTIONS)
                .map(v -> (Map<String, Object>) v)
                .orElse(Map.of());
    }
}
