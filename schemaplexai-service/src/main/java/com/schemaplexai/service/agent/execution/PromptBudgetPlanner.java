package com.schemaplexai.service.agent.execution;

import com.schemaplexai.service.ai.AiModelConfig;

/**
 * Prompt 动态预算规划器。
 */
final class PromptBudgetPlanner {

    private static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 16_384;
    private static final int MIN_PROMPT_BUDGET_CHARS = 6_400;
    private static final int SAFETY_BUFFER_TOKENS = 1_024;

    PromptBudget plan(AiModelConfig config, boolean hasSemanticContext, boolean hasTeamContext, boolean hasRuntimeContext) {
        int contextWindowTokens = config != null ? config.resolvedContextWindowTokens() : DEFAULT_CONTEXT_WINDOW_TOKENS;
        int outputReserveTokens = config != null && config.getMaxTokens() > 0 ? config.getMaxTokens() : 2_048;
        int promptBudgetTokens = Math.max(2_048, contextWindowTokens - outputReserveTokens - SAFETY_BUFFER_TOKENS);
        int totalPromptBudgetChars = Math.max(MIN_PROMPT_BUDGET_CHARS, promptBudgetTokens * 4);

        int l4 = Math.max(1_800, (int) (totalPromptBudgetChars * 0.32));
        int l1 = Math.max(1_200, (int) (totalPromptBudgetChars * 0.18));
        int bound = Math.max(1_000, (int) (totalPromptBudgetChars * 0.16));
        int l25 = hasSemanticContext ? Math.max(800, (int) (totalPromptBudgetChars * 0.14)) : 320;
        int team = hasTeamContext ? Math.max(600, (int) (totalPromptBudgetChars * 0.08)) : 160;
        int l3 = hasRuntimeContext ? Math.max(700, (int) (totalPromptBudgetChars * 0.12)) : 240;

        int used = l4 + l1 + bound + l25 + team + l3;
        int remainder = totalPromptBudgetChars - used;
        if (remainder > 0) {
            l4 += remainder / 3;
            bound += remainder / 3;
            l3 += remainder - (remainder / 3) * 2;
        }

        return new PromptBudget(contextWindowTokens, outputReserveTokens, totalPromptBudgetChars, l4, l1, bound, l25, team, l3);
    }

    record PromptBudget(int contextWindowTokens,
                        int outputReserveTokens,
                        int totalPromptBudgetChars,
                        int l4BudgetChars,
                        int l1BudgetChars,
                        int boundBudgetChars,
                        int l25BudgetChars,
                        int teamBudgetChars,
                        int l3BudgetChars) {
    }
}
