package forge.ai.llm;

import forge.ai.llm.tools.SubmitChoice;
import forge.ai.llm.tools.PayMana;

/** Choice tools compete for the same pending choice; only one response can be accepted. */
sealed interface ChoiceDecision {
    record Choose(SubmitChoice answer) implements ChoiceDecision { }
    record Payment(PayMana plan) implements ChoiceDecision { }
    record Cancel() implements ChoiceDecision { }
}
