package forge.ai.llm.tools;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import java.util.Map;

/** Observe this agent's hand without taking an action. */
public record ObserveHand() {
    public static final String NAME = "get_hand";

    public static Tool schema() {
        return Tool.builder().name(NAME)
                .description("List the cards in your hand, with card IDs, names, owners, controllers, "
                        + "and tapped/face-down status. Does not include opponents' hands. "
                        + "Use get_card_details for printed card rules. An empty list means an empty hand.")
                .annotations(ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(false).build())
                .inputSchema(Map.of("type", "object", "properties", Map.of(), "additionalProperties", false))
                .build();
    }

    public static ObserveHand compile(CallToolRequest request) {
        if (request.arguments() != null && !request.arguments().isEmpty()) {
            throw new IllegalArgumentException("get_hand takes no arguments.");
        }
        return new ObserveHand();
    }
}
