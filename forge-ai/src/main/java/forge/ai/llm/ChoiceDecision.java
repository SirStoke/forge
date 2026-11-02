package forge.ai.llm;

import forge.ai.llm.tools.SubmitChoice;

/** Both tools compete for the same pending choice; only one response can be accepted. */
sealed interface ChoiceDecision {
    record Choose(SubmitChoice answer) implements ChoiceDecision { }
    record Cancel() implements ChoiceDecision { }
}
