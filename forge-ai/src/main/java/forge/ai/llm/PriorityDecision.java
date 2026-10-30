package forge.ai.llm;

/** A priority choice containing only IDs; the game thread resolves it into an action. */
sealed interface PriorityDecision {
    record PlayCard(int cardId, int abilityId) implements PriorityDecision {
    }

    record Pass() implements PriorityDecision {
    }
}
