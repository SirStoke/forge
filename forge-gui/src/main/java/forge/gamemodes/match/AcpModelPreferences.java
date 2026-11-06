package forge.gamemodes.match;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import forge.ai.llm.AcpModels;

import java.io.IOException;

/** Slot- and harness-specific model preferences, separate from network lobby state. */
public final class AcpModelPreferences {
    private static final ObjectMapper JSON = new ObjectMapper();
    private AcpModelPreferences() { }

    public static AcpModels read(String saved, int slot, String agentId) {
        final var entry = parse(saved).path(Integer.toString(slot)).path(agentId);
        return new AcpModels(entry.path("fastModel").asText(""), entry.path("planningModel").asText(""));
    }

    public static String write(String saved, int slot, String agentId, AcpModels models) {
        final var root = parse(saved);
        final var key = Integer.toString(slot);
        final var player = root.path(key) instanceof ObjectNode object ? object : root.putObject(key);
        final var entry = player.putObject(agentId);
        entry.put("fastModel", models.fastModel());
        entry.put("planningModel", models.planningModel());
        return root.toString();
    }

    private static ObjectNode parse(String saved) {
        try {
            if (saved != null && !saved.isBlank() && JSON.readTree(saved) instanceof ObjectNode root) return root;
        } catch (IOException ignored) { }
        return JSON.createObjectNode();
    }
}
