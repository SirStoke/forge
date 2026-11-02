package forge.ai.llm.tools;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.List;
import java.util.Map;

/** Abort the action being prepared at a prompt that explicitly permits cancellation. */
public record CancelAction(String requestId) {
    public static final String NAME = "cancel_action";

    public static Tool schema() {
        return Tool.builder().name(NAME)
                .description("Cancel the action being cast or activated, using the CURRENT follow-up requestId. "
                        + "Only valid when that prompt explicitly offers cancel_action. Shares the pending decision "
                        + "with submit_choice; submit one or the other, then finish your response. Forge aborts through "
                        + "its normal rollback/refund path and asks for another priority action without passing priority. "
                        + "Cannot cancel a completed action, a priority request, or a resolving effect.")
                .inputSchema(Map.of("type", "object", "properties", Map.of(
                        "requestId", Map.of("type", "string", "minLength", 1,
                                "description", "Current cancellable follow-up request ID, not the earlier priority request ID")),
                        "required", List.of("requestId"), "additionalProperties", false))
                .build();
    }

    public static CancelAction compile(CallToolRequest request) {
        final Map<String, Object> arguments = request.arguments();
        if (arguments == null || arguments.size() != 1
                || !(arguments.get("requestId") instanceof String requestId) || requestId.isBlank()) {
            throw new IllegalArgumentException("Expected exactly one nonblank string requestId.");
        }
        return new CancelAction(requestId);
    }
}
