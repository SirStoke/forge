package forge.ai.llm;

/** A reusable operation with typed input and output. */
@FunctionalInterface
public interface Callback<I, O> {
    O call(I input);
}
