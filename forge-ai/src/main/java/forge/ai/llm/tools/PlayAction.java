package forge.ai.llm.tools;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.List;
import java.util.Map;

/** Select a spell, land play, activated ability or special action from the pending priority request. */
public record PlayAction(String requestId, int cardId, int abilityId) {
    public static final String NAME = "play_action";

    public static Tool schema() {
        return Tool.builder().name(NAME)
                .description("Choose a spell to cast, land to play, ability to activate, or special action "
                        + "from any permitted zone for the pending priority request. "
                        + "Use a cardId and abilityId pair returned by get_available_actions for that request. "
                        + "Forge executes the choice after your response and requests follow-up choices through submit_choice.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "requestId", Map.of("type", "string", "minLength", 1,
                                        "description", "Request ID from the priority prompt"),
                                "cardId", Map.of("type", "integer", "minimum", 0,
                                        "description", "ID of the source card instance returned by get_available_actions"),
                                "abilityId", Map.of("type", "integer", "minimum", 0,
                                        "description", "ID of the action returned by get_available_actions")),
                        "required", List.of("requestId", "cardId", "abilityId"),
                        "additionalProperties", false))
                .build();
    }

    public static PlayAction compile(CallToolRequest request) {
        final Map<String, Object> arguments = request.arguments();
        if (arguments == null || arguments.size() != 3
                || !(arguments.get("requestId") instanceof String requestId) || requestId.isBlank()) {
            throw new IllegalArgumentException("Expected a nonblank requestId and integer cardId and abilityId.");
        }
        return new PlayAction(requestId, id(arguments.get("cardId")), id(arguments.get("abilityId")));
    }

    private static int id(Object value) {
        if ((value instanceof Integer || value instanceof Long) && ((Number) value).longValue() >= 0
                && ((Number) value).longValue() <= Integer.MAX_VALUE) {
            return ((Number) value).intValue();
        }
        throw new IllegalArgumentException("cardId and abilityId must be nonnegative 32-bit integers.");
    }
}
