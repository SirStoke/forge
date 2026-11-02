package forge.ai.llm.tools;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import java.util.Map;

/** Inspect this agent's registered deck composition without taking an action. */
public record ObserveDeck() {
    public static final String NAME = "get_deck";

    public static Tool schema() {
        return Tool.builder().name(NAME)
                .description("Read your registered deck list for this game: deck name and sections (Main, Sideboard, "
                        + "Commander and any variant sections), each with totalCards and alphabetically sorted "
                        + "card names and copy counts. Different printings of the same card are combined. "
                        + "This is your full registered composition, not the cards remaining in your library: "
                        + "draws and shuffles do not change it. Does not reveal draw order or opponents' decks. "
                        + "Use get_card_details for printed card rules. Takes no arguments and does not submit a decision.")
                .annotations(ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(false).build())
                .inputSchema(Map.of("type", "object", "properties", Map.of(), "additionalProperties", false))
                .build();
    }

    public static ObserveDeck compile(CallToolRequest request) {
        if (request.arguments() != null && !request.arguments().isEmpty()) {
            throw new IllegalArgumentException("get_deck takes no arguments.");
        }
        return new ObserveDeck();
    }
}
