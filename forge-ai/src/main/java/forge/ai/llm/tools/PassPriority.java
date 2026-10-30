package forge.ai.llm.tools;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.List;
import java.util.Map;

/** A request to pass priority without casting a spell or activating an ability. */
public record PassPriority(String requestId) {
    public static final String NAME = "pass_priority";

    public static Tool schema() {
        return Tool.builder().name(NAME)
                .description("Pass priority without casting a spell or activating an ability for the pending request.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of("requestId", Map.of("type", "string",
                                "description", "Request ID from the priority prompt")),
                        "required", List.of("requestId"),
                        "additionalProperties", false))
                .build();
    }

    public static PassPriority compile(CallToolRequest request) {
        final Map<String, Object> arguments = request.arguments();
        if (arguments == null || arguments.size() != 1
                || !(arguments.get("requestId") instanceof String requestId)) {
            throw new IllegalArgumentException("Expected exactly one string requestId.");
        }
        return new PassPriority(requestId);
    }
}
