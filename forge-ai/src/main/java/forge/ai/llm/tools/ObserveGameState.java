package forge.ai.llm.tools;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import java.util.Map;

/** Observe the public match state without taking an action. */
public record ObserveGameState() {
    public static final String NAME = "get_game_state";

    public static Tool schema() {
        return Tool.builder().name(NAME)
                .description("Read the current turn, phase, active/priority players, and your player ID. "
                        + "For every player, includes life, counters, floating mana by color, hand/library sizes, "
                        + "lands played this turn, graveyard cards and visible exile cards. "
                        + "Combat includes attacker IDs, defenders, blocked status and blocker IDs; use get_battlefield "
                        + "and get_card_details for their current stats and rules. "
                        + "Does not expose hidden card identities or library order.")
                .annotations(ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(false).build())
                .inputSchema(Map.of("type", "object", "properties", Map.of(), "additionalProperties", false))
                .build();
    }

    public static ObserveGameState compile(CallToolRequest request) {
        if (request.arguments() != null && !request.arguments().isEmpty()) {
            throw new IllegalArgumentException("get_game_state takes no arguments.");
        }
        return new ObserveGameState();
    }
}
