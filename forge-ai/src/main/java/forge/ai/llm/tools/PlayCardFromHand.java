package forge.ai.llm.tools;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.List;
import java.util.Map;

/** Select a spell or land play offered by the pending priority request. */
public record PlayCardFromHand(String requestId, int cardId, int abilityId) {
    public static final String NAME = "play_card_from_hand";

    public static Tool schema() {
        return Tool.builder().name(NAME)
                .description("Choose a card to cast or a land to play from your hand for the pending priority request. "
                        + "Use a cardId and abilityId pair from that request's available plays. "
                        + "Forge executes the choice after your response; its built-in AI handles targets and payment.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "requestId", Map.of("type", "string", "minLength", 1,
                                        "description", "Request ID from the priority prompt"),
                                "cardId", Map.of("type", "integer", "minimum", 0,
                                        "description", "ID of the card instance in your hand"),
                                "abilityId", Map.of("type", "integer", "minimum", 0,
                                        "description", "ID of the casting or land-play option from the priority prompt")),
                        "required", List.of("requestId", "cardId", "abilityId"),
                        "additionalProperties", false))
                .build();
    }

    public static PlayCardFromHand compile(CallToolRequest request) {
        final Map<String, Object> arguments = request.arguments();
        if (arguments == null || arguments.size() != 3
                || !(arguments.get("requestId") instanceof String requestId) || requestId.isBlank()) {
            throw new IllegalArgumentException("Expected a nonblank requestId and integer cardId and abilityId.");
        }
        return new PlayCardFromHand(requestId, id(arguments.get("cardId")), id(arguments.get("abilityId")));
    }

    private static int id(Object value) {
        if ((value instanceof Integer || value instanceof Long) && ((Number) value).longValue() >= 0
                && ((Number) value).longValue() <= Integer.MAX_VALUE) {
            return ((Number) value).intValue();
        }
        throw new IllegalArgumentException("cardId and abilityId must be nonnegative 32-bit integers.");
    }
}
