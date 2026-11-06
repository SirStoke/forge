package forge.ai.llm.tools;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.List;
import java.util.Map;

/** Request-local main/sideboard option indices, repeated once per copy to move. */
public record SubmitSideboardPlan(String requestId, List<Integer> out, List<Integer> in) {
    public static final String NAME = "submit_sideboard_plan";

    public SubmitSideboardPlan {
        out = List.copyOf(out);
        in = List.copyOf(in);
    }

    public static Tool schema() {
        final var indices = Map.of("type", "array", "items", Map.of("type", "integer", "minimum", 0));
        return Tool.builder().name(NAME)
                .description("Submit a sideboarding plan for the pending request. out contains Main option indices; "
                        + "in contains Sideboard option indices. Repeat an index once per copy moved, up to the "
                        + "offered count. Use empty out and in arrays to keep your deck. Resulting deck sizes must "
                        + "satisfy the prompt. This tool is only available for a pending sideboarding decision.")
                .inputSchema(Map.of("type", "object", "properties", Map.of(
                        "requestId", Map.of("type", "string", "minLength", 1), "out", indices, "in", indices),
                        "required", List.of("requestId", "out", "in"), "additionalProperties", false))
                .build();
    }

    public static SubmitSideboardPlan compile(CallToolRequest request) {
        final var args = request.arguments();
        if (args == null || args.size() != 3 || !(args.get("requestId") instanceof String id) || id.isBlank()
                || !(args.get("out") instanceof List<?> out) || !(args.get("in") instanceof List<?> in)) {
            throw new IllegalArgumentException("Supply requestId, out and in arrays of option indices.");
        }
        return new SubmitSideboardPlan(id, out.stream().map(SubmitSideboardPlan::index).toList(),
                in.stream().map(SubmitSideboardPlan::index).toList());
    }

    private static int index(Object value) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
                || ((Number) value).longValue() < 0 || ((Number) value).longValue() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Expected a nonnegative 32-bit option index.");
        }
        return ((Number) value).intValue();
    }
}
