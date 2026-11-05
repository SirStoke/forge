package forge.ai.llm.tools;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.List;
import java.util.Map;

/** A bounded, ordered plan referencing only the pending mana-payment menu. */
public record PayMana(String requestId, List<Step> steps) {
    public static final String NAME = "pay_mana";
    public static final int MAX_STEPS = 256;
    private static final List<String> COLORS = List.of("W", "U", "B", "R", "G", "C");

    public PayMana {
        if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("Supply a nonblank requestId.");
        steps = List.copyOf(steps);
        if (steps.isEmpty() || steps.size() > MAX_STEPS) throw new IllegalArgumentException("Supply 1..256 payment steps.");
    }

    public record Step(int option, List<String> colors) {
        public Step {
            if (option < 0) throw new IllegalArgumentException("option must be a nonnegative integer.");
            colors = List.copyOf(colors);
            if (colors.size() > MAX_STEPS || !COLORS.containsAll(colors)) {
                throw new IllegalArgumentException("colors must contain at most 256 W/U/B/R/G/C strings.");
            }
        }
    }

    public static Tool schema() {
        return Tool.builder().name(NAME)
                .description("Submit an ordered batch of payment steps for the CURRENT mana-payment prompt. "
                        + "Each step references an offered option index: activate a mana source, spend floating mana, "
                        + "or pay permitted life. For source activation, optionally supply colors (W/U/B/R/G/C) for "
                        + "its production choices in order. Forge validates and executes on the game thread after "
                        + "your response, automatically spending eligible mana newly produced by selected sources. "
                        + "Existing floating mana is spent only when selected. Stops when paid, leaving surplus mana "
                        + "and unneeded sources untouched. Missing/invalid color choices or additional source costs "
                        + "can prompt again; unavailable steps pause the plan. Shares the pending request with "
                        + "submit_choice and cancel_action: submit exactly one decision. "
                        + "Only valid when the prompt offers pay_mana.")
                .inputSchema(Map.of("type", "object", "properties", Map.of(
                        "requestId", Map.of("type", "string", "minLength", 1),
                        "steps", Map.of("type", "array", "minItems", 1, "maxItems", MAX_STEPS,
                                "items", Map.of("type", "object", "properties", Map.of(
                                        "option", Map.of("type", "integer", "minimum", 0),
                                        "colors", Map.of("type", "array", "maxItems", MAX_STEPS,
                                                "items", Map.of("type", "string", "enum", COLORS))),
                                        "required", List.of("option"), "additionalProperties", false))),
                        "required", List.of("requestId", "steps"), "additionalProperties", false))
                .build();
    }

    public static PayMana compile(CallToolRequest request) {
        final var args = request.arguments();
        if (args == null || args.size() != 2 || !(args.get("requestId") instanceof String id)
                || !(args.get("steps") instanceof List<?> steps)) {
            throw new IllegalArgumentException("Supply requestId and steps.");
        }
        if (steps.isEmpty() || steps.size() > MAX_STEPS) throw new IllegalArgumentException("Supply 1..256 payment steps.");
        return new PayMana(id, steps.stream().map(PayMana::step).toList());
    }

    private static Step step(Object value) {
        if (!(value instanceof Map<?, ?> fields) || !fields.containsKey("option")
                || fields.keySet().stream().anyMatch(key -> !List.of("option", "colors").contains(key))) {
            throw new IllegalArgumentException("Each step requires option and optionally colors.");
        }
        Object index = fields.get("option");
        if (!(index instanceof Integer || index instanceof Long) || ((Number) index).longValue() < 0
                || ((Number) index).longValue() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("option must be a nonnegative 32-bit integer.");
        }
        List<String> colors = List.of();
        if (fields.containsKey("colors")) {
            if (!(fields.get("colors") instanceof List<?> values) || values.stream().anyMatch(color -> !(color instanceof String))) {
                throw new IllegalArgumentException("colors must be an array of W/U/B/R/G/C strings.");
            }
            colors = values.stream().map(String.class::cast).toList();
        }
        return new Step(((Number) index).intValue(), colors);
    }
}
