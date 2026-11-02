package forge.ai.llm.tools;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import java.util.List;
import java.util.Map;

/** Read the pending priority request's options without submitting a decision. */
public record GetAvailableActions(String requestId) {
    public static final String NAME = "get_available_actions";

    public static Tool schema() {
        return Tool.builder().name(NAME)
                .description("List actions for the pending priority request: spells and land plays from permitted zones, "
                        + "activated abilities (including mana abilities), and special actions. Returns cardId, abilityId, "
                        + "name, zone, kind, cost and description. Timing and permissions are checked; cost affordability "
                        + "is NOT checked. Payment may fail. Read as often as needed, then call play_action or pass_priority. "
                        + "The list expires when the priority decision is accepted or the request ends.")
                .annotations(ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(false).build())
                .inputSchema(Map.of("type", "object", "properties", Map.of(
                        "requestId", Map.of("type", "string", "minLength", 1,
                                "description", "Request ID from the priority prompt")),
                        "required", List.of("requestId"), "additionalProperties", false))
                .build();
    }

    public static GetAvailableActions compile(CallToolRequest request) {
        final Map<String, Object> arguments = request.arguments();
        if (arguments == null || arguments.size() != 1
                || !(arguments.get("requestId") instanceof String requestId) || requestId.isBlank()) {
            throw new IllegalArgumentException("Expected exactly one nonblank string requestId.");
        }
        return new GetAvailableActions(requestId);
    }
}
