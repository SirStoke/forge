package forge.ai.llm.tools;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import java.util.Map;

/** Observe all players' battlefield cards without taking an action. */
public record ObserveBattlefield() {
    public static final String NAME = "get_battlefield";

    public static Tool schema() {
        return Tool.builder().name(NAME)
                .description("List all players' cards on the battlefield, with card IDs, names, owners, "
                        + "controllers, and tapped/face-down status. Hidden identities are concealed. "
                        + "Use get_card_details for printed card rules. An empty list means an empty battlefield.")
                .annotations(ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(false).build())
                .inputSchema(Map.of("type", "object", "properties", Map.of(), "additionalProperties", false))
                .build();
    }

    public static ObserveBattlefield compile(CallToolRequest request) {
        if (request.arguments() != null && !request.arguments().isEmpty()) {
            throw new IllegalArgumentException("get_battlefield takes no arguments.");
        }
        return new ObserveBattlefield();
    }
}
