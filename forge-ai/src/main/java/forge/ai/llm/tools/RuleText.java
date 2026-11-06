package forge.ai.llm.tools;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import java.util.List;
import java.util.Map;

/** Read an exact numbered rule, independently of game state. */
public record RuleText(String id) {
    public static final String NAME = "get_rule";

    public static Tool schema() {
        return Tool.builder().name(NAME)
                .description("Read one numbered paragraph from the bundled MTG Comprehensive Rules, including "
                        + "its examples and continuation text. Accepts chapter, section, rule or subrule ids "
                        + "such as 6, 601, 601.2 or 601.2a (a trailing period is allowed). Returns only that "
                        + "entry's own text, not descendants or adjacent paragraphs. Use get_rules_index with "
                        + "parent set to this id to browse subrules. Includes the rules edition and source URL. "
                        + "Does not submit a gameplay decision.")
                .annotations(ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(false).build())
                .inputSchema(Map.of("type", "object", "properties", Map.of(
                        "id", Map.of("type", "string", "minLength", 1,
                                "description", "Exact rule number, for example 702.2a.")),
                        "required", List.of("id"), "additionalProperties", false))
                .build();
    }

    public static RuleText compile(CallToolRequest request) {
        final Map<String, Object> args = request.arguments();
        if (args == null || args.size() != 1 || !args.containsKey("id")) {
            throw new IllegalArgumentException("Expected exactly one string id, for example 702.2a.");
        }
        return new RuleText(ruleId(args.get("id")));
    }

    static String ruleId(Object value) {
        if (!(value instanceof String text) || !text.strip().matches("[1-9](?:[0-9]{2}(?:\\.[0-9]+[a-z]*)?)?\\.?")) {
            throw new IllegalArgumentException("Expected a numbered rule id, for example 6, 601, 601.2 or 601.2a.");
        }
        final String id = text.strip();
        return id.endsWith(".") ? id.substring(0, id.length() - 1) : id;
    }
}
