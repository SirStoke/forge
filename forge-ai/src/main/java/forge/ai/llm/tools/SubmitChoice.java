package forge.ai.llm.tools;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.List;
import java.util.Map;

/** A response contains either an ordered list of request-local option indices or one integer. */
public record SubmitChoice(String requestId, List<Integer> choices, Integer value) {
    public SubmitChoice {
        choices = choices == null ? null : List.copyOf(choices);
    }

    public static final String NAME = "submit_choice";

    public static Tool schema() {
        return Tool.builder().name(NAME)
                .description("Answer the pending Forge choice. Use choices (an ordered array of option indices) "
                        + "for selection, ordering, targets, modes, costs, and combat; use value for a number. "
                        + "Supply exactly one of choices or value. Only options and bounds in the current prompt are valid.")
                .inputSchema(Map.of("type", "object", "properties", Map.of(
                        "requestId", Map.of("type", "string", "minLength", 1),
                        "choices", Map.of("type", "array", "items", Map.of("type", "integer", "minimum", 0)),
                        "value", Map.of("type", "integer")),
                        "required", List.of("requestId"), "additionalProperties", false,
                        "oneOf", List.of(Map.of("required", List.of("choices")), Map.of("required", List.of("value")))))
                .build();
    }

    public static SubmitChoice compile(CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        if (args == null || args.size() != 2 || !(args.get("requestId") instanceof String id) || id.isBlank()) {
            throw new IllegalArgumentException("Supply requestId and exactly one of choices or value.");
        }
        if (args.get("choices") instanceof List<?> values) {
            return new SubmitChoice(id, values.stream().map(SubmitChoice::integer).toList(), null);
        }
        if (args.containsKey("value")) {
            return new SubmitChoice(id, null, integer(args.get("value")));
        }
        throw new IllegalArgumentException("Supply choices or value.");
    }

    private static int integer(Object value) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
                || ((Number) value).longValue() < Integer.MIN_VALUE || ((Number) value).longValue() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Expected a 32-bit integer.");
        }
        return ((Number) value).intValue();
    }
}
