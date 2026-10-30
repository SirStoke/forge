package forge.ai.llm.tools;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.List;
import java.util.Map;

/** A validated submission for a pending mulligan request. */
public record Mulligan(String requestId, boolean keep) {
    public static final String NAME = "submit_mulligan_decision";

    public static Tool schema() {
        return Tool.builder().name(NAME)
                .description("Submit Keep (true) or Mulligan (false) for the pending mulligan request.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "requestId", Map.of("type", "string", "description", "Request ID from the prompt"),
                                "keep", Map.of("type", "boolean", "description", "True to keep, false to mulligan")),
                        "required", List.of("requestId", "keep"),
                        "additionalProperties", false))
                .build();
    }

    public static Mulligan compile(CallToolRequest request) {
        final Map<String, Object> arguments = request.arguments();
        if (arguments == null || arguments.size() != 2
                || !(arguments.get("requestId") instanceof String requestId)
                || !(arguments.get("keep") instanceof Boolean keep)) {
            throw new IllegalArgumentException("Expected exactly a string requestId and a boolean keep.");
        }
        return new Mulligan(requestId, keep);
    }
}
