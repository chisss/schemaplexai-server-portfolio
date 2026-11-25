package com.schemaplexai.service.agent.execution;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentAcceptanceServiceTest {

    private final AgentAcceptance contract = new AgentAcceptance(Map.of(
            "/result/count", "integer", "/result/ok", "boolean", "/a~1b", "null"));

    @Test
    void acceptsExactlyOneTypedJsonValue() {
        String output = """
                {"result":{"count":2,"ok":true},"a/b":null}
                """;
        assertEquals(AgentAcceptance.Status.ACCEPTED, contract.evaluate(output, false).status());
        assertEquals(AgentAcceptance.Status.PARTIAL, contract.evaluate(output, true).status());
        assertEquals(AgentAcceptance.Status.REJECTED, contract.evaluate(output + "{}", false).status());
    }

    @Test
    void distinguishesMissingOutputFromOutputThatFailedValidation() {
        assertEquals(AgentAcceptance.Status.UNVERIFIED, contract.evaluate(" ", false).status());
        assertEquals(AgentAcceptance.Status.REJECTED,
                contract.evaluate("{\"result\":{\"count\":2,\"ok\":\"true\"},\"a/b\":null}", false).status());
        assertEquals(AgentAcceptance.Status.REJECTED,
                contract.evaluate("{\"result\":{\"count\":2,\"ok\":true}}", false).status());
    }

    @Test
    void restoresRulesFromTheExecutionSnapshot() {
        assertNull(AgentAcceptance.fromSnapshot(Map.of()));
        assertEquals(contract, AgentAcceptance.fromSnapshot(
                Map.of(AgentAcceptance.SNAPSHOT_KEY, contract.requiredJson())));
        assertThrows(IllegalArgumentException.class, () -> new AgentAcceptance(Map.of("/a~2", "any")));
        assertThrows(IllegalArgumentException.class, () -> new AgentAcceptance(Map.of("/a", "unknown")));
    }
}
