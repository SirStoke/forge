package forge.ai.llm.tools;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import java.util.Map;
import java.util.Set;

/** Browse one level of the numbered Comprehensive Rules at a time. */
public record RulesIndex(String parent, int offset) {
    public static final String NAME = "get_rules_index";
    public static final int PAGE_SIZE = 50;

    public static Tool schema() {
        return Tool.builder().name(NAME)
                .description("Browse the bundled MTG Comprehensive Rules without loading the rulebook. Omit parent "
                        + "to list chapters; pass a chapter (6), section (601), or paragraph (601.2) to list its "
                        + "immediate children, with short previews and child counts. Returns up to 50 entries in "
                        + "book order; use nextOffset for the next page. Use get_rule with an entry's id to read "
                        + "its exact text. No game or pending decision is needed.")
                .annotations(ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(false).build())
                .inputSchema(Map.of("type", "object", "properties", Map.of(
                        "parent", Map.of("type", "string", "minLength", 1,
                                "description", "Parent rule id, for example 6, 601, or 601.2. Omit for chapters."),
                        "offset", Map.of("type", "integer", "minimum", 0,
                                "description", "Zero-based starting entry; defaults to 0.")),
                        "additionalProperties", false))
                .build();
    }

    public static RulesIndex compile(CallToolRequest request) {
        final Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
        if (!Set.of("parent", "offset").containsAll(args.keySet())) {
            throw new IllegalArgumentException("Expected only optional parent and offset.");
        }
        final String parent = args.containsKey("parent") ? RuleText.ruleId(args.get("parent")) : "";
        int offset = 0;
        if (args.containsKey("offset")) {
            final Object value = args.get("offset");
            if (!(value instanceof Integer || value instanceof Long)
                    || ((Number) value).longValue() < 0 || ((Number) value).longValue() > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("offset must be a non-negative integer.");
            }
            offset = ((Number) value).intValue();
        }
        return new RulesIndex(parent, offset);
    }
}
