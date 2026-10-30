package forge.ai.llm.tools;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import java.util.Map;

/** Observe the game stack without taking an action. */
public record ObserveStack() {
    public static final String NAME = "get_stack";

    public static Tool schema() {
        return Tool.builder().name(NAME)
                .description("Observe the current game stack. Items are ordered top first (next to resolve), "
                        + "with IDs, sources, controllers, descriptions, and targets. An empty list means an empty stack.")
                .annotations(ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(false).build())
                .inputSchema(Map.of("type", "object", "properties", Map.of(), "additionalProperties", false))
                .build();
    }

    public static ObserveStack compile(CallToolRequest request) {
        if (request.arguments() != null && !request.arguments().isEmpty()) {
            throw new IllegalArgumentException("get_stack takes no arguments.");
        }
        return new ObserveStack();
    }
}
