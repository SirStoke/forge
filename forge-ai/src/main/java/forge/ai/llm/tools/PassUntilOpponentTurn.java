package forge.ai.llm.tools;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.List;
import java.util.Map;

/** Pass the pending priority decision and yield further priority until an opponent's turn begins. */
public record PassUntilOpponentTurn(String requestId) {
    public static final String NAME = "pass_until_opponent_turn";

    public static Tool schema() {
        return Tool.builder().name(NAME)
                .description("During your own turn, pass the current priority request and automatically pass "
                        + "further priority until an opponent's turn begins, including if the stack changes. "
                        + "Normal priority decisions resume on that opponent's turn. Required non-priority choices "
                        + "such as combat, resolving effects and discards still need decisions. Supply the current "
                        + "priority requestId. On an opponent's turn use pass_priority instead.")
                .inputSchema(Map.of("type", "object", "properties", Map.of(
                        "requestId", Map.of("type", "string", "minLength", 1,
                                "description", "Request ID from the current priority prompt on your own turn")),
                        "required", List.of("requestId"), "additionalProperties", false))
                .build();
    }

    public static PassUntilOpponentTurn compile(CallToolRequest request) {
        final var args = request.arguments();
        if (args == null || args.size() != 1 || !(args.get("requestId") instanceof String id) || id.isBlank()) {
            throw new IllegalArgumentException("Expected exactly one nonblank string requestId.");
        }
        return new PassUntilOpponentTurn(id);
    }
}
