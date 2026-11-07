package forge.ai.llm.tools;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import java.util.List;
import java.util.Map;

/** Append strategic notes that survive this player's fresh ACP conversations. */
public record SaveNotes(String text) {
    public static final String NAME = "save_notes";

    public static Tool schema() {
        return Tool.builder().name(NAME)
                .description("Append private strategic notes for your current game. All saved text is included "
                        + "in every subsequent decision prompt, across fresh conversations. Before submitting each "
                        + "decision, save your game plan, threat response, intended winning line, or a brief update "
                        + "confirming the existing plan. Earlier notes are preserved; explicitly correct outdated plans. "
                        + "This does not submit a gameplay decision or advance the game.")
                .annotations(ToolAnnotations.builder().readOnlyHint(false).destructiveHint(false)
                        .idempotentHint(false).openWorldHint(false).build())
                .inputSchema(Map.of("type", "object", "properties", Map.of(
                        "text", Map.of("type", "string", "minLength", 1)),
                        "required", List.of("text"), "additionalProperties", false))
                .build();
    }

    public static SaveNotes compile(CallToolRequest request) {
        final var args = request.arguments();
        if (args == null || args.size() != 1 || !(args.get("text") instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Supply nonblank text to append to your notes.");
        }
        return new SaveNotes(text);
    }
}
