package forge.ai.llm.tools;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import java.util.List;
import java.util.Map;

/** A lookup of generic card rules, independent of any card in the game. */
public record CardDetails(String name) {
    public static final String NAME = "get_card_details";

    public static Tool schema() {
        return Tool.builder().name(NAME)
                .description("Look up any card by its English name, including alternate-face names. Returns printed "
                        + "mana cost, type, colors, effects (rules text), and stats for every face. No game state is needed.")
                .annotations(ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(false).build())
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of("name", Map.of("type", "string", "minLength", 1,
                                "pattern", "\\S", "description", "Card name, for example Lightning Bolt")),
                        "required", List.of("name"),
                        "additionalProperties", false))
                .build();
    }

    public static CardDetails compile(CallToolRequest request) {
        final Map<String, Object> arguments = request.arguments();
        if (arguments == null || arguments.size() != 1
                || !(arguments.get("name") instanceof String name) || name.isBlank()) {
            throw new IllegalArgumentException("Expected exactly one non-blank string name.");
        }
        return new CardDetails(name.strip());
    }
}
