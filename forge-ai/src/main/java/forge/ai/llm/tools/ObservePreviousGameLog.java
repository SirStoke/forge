package forge.ai.llm.tools;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import java.util.Map;

/** Paginate the completed game's frozen log without inspecting any opponent deck. */
public record ObservePreviousGameLog(int offset, int limit) {
    public static final String NAME = "get_previous_game_log";

    public static Tool schema() {
        return Tool.builder().name(NAME)
                .description("Read the last completed game's log in this match, in chronological order, plus its "
                        + "winner. Useful for sideboarding based on spells, threats and damage seen last game. "
                        + "Does not reveal opponents' deck lists. Optional offset (default 0) and limit (default 100, "
                        + "maximum 200) paginate entries; continue until offset + entries.length reaches totalEntries. "
                        + "available is false before any game has completed. Does not submit a decision.")
                .annotations(ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(false).build())
                .inputSchema(Map.of("type", "object", "properties", Map.of(
                        "offset", Map.of("type", "integer", "minimum", 0),
                        "limit", Map.of("type", "integer", "minimum", 1, "maximum", 200)),
                        "additionalProperties", false))
                .build();
    }

    public static ObservePreviousGameLog compile(CallToolRequest request) {
        final var args = request.arguments() == null ? Map.<String, Object>of() : request.arguments();
        if (args.keySet().stream().anyMatch(key -> !key.equals("offset") && !key.equals("limit"))) {
            throw new IllegalArgumentException("Only offset and limit are accepted.");
        }
        return new ObservePreviousGameLog(integer(args.getOrDefault("offset", 0), 0, Integer.MAX_VALUE),
                integer(args.getOrDefault("limit", 100), 1, 200));
    }

    private static int integer(Object value, int min, int max) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
                || ((Number) value).longValue() < min || ((Number) value).longValue() > max) {
            throw new IllegalArgumentException("Expected an integer between " + min + " and " + max + ".");
        }
        return ((Number) value).intValue();
    }
}
