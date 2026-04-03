package com.schemaplexai.service.agent.runtime.team;

/**
 * Team Graph 常量
 */
public final class TeamGraphConstants {

    private TeamGraphConstants() {
    }

    public static final String NODE_PLAN = "plan";
    public static final String NODE_MEMBER_CYCLE = "member_cycle";
    public static final String NODE_EXECUTE_MEMBERS = "execute_members";
    public static final String NODE_AGGREGATE = "aggregate";
    public static final String NODE_RETRY = "retry";
    public static final String NODE_AWAIT_INPUT = "await_input";

    public static final String STATE_PLAN_SUMMARY = "planSummary";
    public static final String STATE_INPUT_PROMPT = "inputPrompt";
    public static final String STATE_MEMBER_RESULTS = "memberResults";
    public static final String STATE_FINAL_STATUS = "finalStatus";
    public static final String STATE_FINAL_OUTPUT = "finalOutput";
    public static final String STATE_FINAL_ERROR = "finalError";
    public static final String STATE_RETRY_COUNT = "retryCount";
    public static final String STATE_USER_INPUT = "userInput";
    public static final String STATE_USER_OPTIONS = "userOptions";

    public static final String RESULT_MEMBER_ID = "memberId";
    public static final String RESULT_ROLE_NAME = "roleName";
    public static final String RESULT_ROLE_TYPE = "roleType";
    public static final String RESULT_MODEL = "model";
    public static final String RESULT_EXECUTION_ID = "executionId";
    public static final String RESULT_STATUS = "status";
    public static final String RESULT_OUTPUT_RESULT = "outputResult";
    public static final String RESULT_ERROR_MESSAGE = "errorMessage";
    public static final String RESULT_CONVERSATION_ID = "conversationId";

    public static final String EVENT_PAYLOAD_GRAPH_THREAD_ID = "graphThreadId";
    public static final String EVENT_PAYLOAD_CHECKPOINT_NAMESPACE = "checkpointNamespace";
    public static final String EVENT_PAYLOAD_FINAL_OUTPUT = "finalOutput";
}
