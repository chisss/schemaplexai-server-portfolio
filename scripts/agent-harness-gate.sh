#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

tests=(
  AgentExecutionEngineTest
  AgentRuntimeOrchestratorTest
  AgentAcceptanceServiceTest
  TeamAgentRuntimeStrategyTest
  AgentModelUsageTest
  AgentModelRequestPlannerTest
  ToolApprovalBatchServiceTest
  DesktopToolBrokerTest
  ExecutionEventStreamServiceTest
  TrajectoryCollectorTest
  TrajectoryJudgeTest
)
selection=$(IFS=,; echo "${tests[*]}")
mvn -B -pl schemaplexai-service -am -Dsurefire.failIfNoSpecifiedTests=false "-Dtest=$selection" "$@" test
