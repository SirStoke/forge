package forge.ai.llm;

import com.samskivert.mustache.Template;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.Map;

final class PriorityPrompt {
    private static final Template TEMPLATE = PromptTemplates.load("priority.mustache");

    private PriorityPrompt() {
    }

    static String render(Player player, String requestId, Map<PriorityDecision.PlayCard, SpellAbility> options) {
        final var phase = player.getGame().getPhaseHandler();
        return TEMPLATE.execute(Map.of(
                "requestId", requestId,
                "playerName", player.getName(),
                "turnPlayerName", phase.getPlayerTurn().getName(),
                "phase", phase.getPhase().toString(),
                "options", options.entrySet().stream().map(entry -> Map.of(
                        "cardId", entry.getKey().cardId(), "abilityId", entry.getKey().abilityId(),
                        "name", entry.getValue().getHostCard().getName(),
                        "description", entry.getValue().getDescription())).toList()));
    }
}
