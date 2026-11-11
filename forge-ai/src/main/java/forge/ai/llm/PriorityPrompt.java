package forge.ai.llm;

import com.samskivert.mustache.Template;
import forge.game.player.Player;

import java.util.Map;

final class PriorityPrompt {
    private static final Template TEMPLATE = PromptTemplates.load("priority.mustache");

    private PriorityPrompt() { }

    static String render(Player player, PriorityActionsSnapshot actions) {
        final var phase = player.getGame().getPhaseHandler();
        return TEMPLATE.execute(Map.of(
                "requestId", actions.requestId(),
                "playerName", player.getName(),
                "turnPlayerName", phase.getPlayerTurn().getName(),
                "phase", phase.getPhase().toString(),
                "actions", actions.actions()));
    }
}
