package com.schemaplexai.service.agent.runtime.team;

import org.bsc.langgraph4j.state.AgentState;

import java.util.List;
import java.util.Map;

/**
 * Team Graph 状态
 */
public class TeamGraphState extends AgentState {

    static final String MEMBER_CONTEXTS = "memberSystemContexts";
    static final String MEMBER_TIMEOUT_MINUTES = "memberTimeoutMinutes";
    static final String MAX_MEMBER_RETRIES = "maxMemberRetries";

    static final String ATTACHMENT_IDS = "attachmentIds";

    public TeamGraphState(Map<String, Object> initData) {
        super(initData);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> memberResults() {
        return value(TeamGraphConstants.STATE_MEMBER_RESULTS)
                .map(v -> (List<Map<String, Object>>) v)
                .orElse(List.of());
    }

    public List<String> attachmentIds() {
        return value(ATTACHMENT_IDS)
                .filter(List.class::isInstance)
                .map(raw -> ((List<?>) raw).stream().map(String.class::cast).toList())
                .orElse(List.of());
    }

    @SuppressWarnings("unchecked")
    Map<String, List<String>> memberSystemContexts() {
        return value(MEMBER_CONTEXTS).map(raw -> (Map<String, List<String>>) raw).orElse(Map.of());
    }

    int memberTimeoutMinutes() {
        return value(MEMBER_TIMEOUT_MINUTES, 10);
    }

    int maxMemberRetries() {
        return value(MAX_MEMBER_RETRIES, 2);
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

    public int schemaVersion() {
        return value(TeamGraphConstants.STATE_SCHEMA_VERSION, 0);
    }

    public List<String> advisorProcessedExecutionIds() {
        return value(TeamGraphConstants.STATE_ADVISOR_PROCESSED_EXECUTION_IDS)
                .filter(List.class::isInstance)
                .map(raw -> ((List<?>) raw).stream().map(String.class::cast).toList())
                .orElse(List.of());
    }

    public List<String> advisorSkippedMemberIds() {
        return value(TeamGraphConstants.STATE_ADVISOR_SKIPPED_MEMBER_IDS)
                .filter(List.class::isInstance)
                .map(raw -> ((List<?>) raw).stream().map(String.class::cast).toList())
                .orElse(List.of());
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
