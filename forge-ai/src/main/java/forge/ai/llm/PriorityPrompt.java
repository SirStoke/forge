package forge.ai.llm;

import com.samskivert.mustache.Template;
import forge.card.CardStateName;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.Map;

final class PriorityPrompt {
    private static final Template TEMPLATE = PromptTemplates.load("priority.mustache");

    private PriorityPrompt() {
    }

    static String render(Player player, String requestId, Map<PriorityDecision.PlayAction, SpellAbility> options) {
        final var phase = player.getGame().getPhaseHandler();
        return TEMPLATE.execute(Map.of(
                "requestId", requestId,
                "playerName", player.getName(),
                "turnPlayerName", phase.getPlayerTurn().getName(),
                "phase", phase.getPhase().toString(),
                "options", options.entrySet().stream().map(entry -> Map.of(
                        "cardId", entry.getKey().cardId(), "abilityId", entry.getKey().abilityId(),
                        "name", name(entry.getValue().getHostCard(), player),
                        "zone", entry.getValue().getHostCard().getZone().getZoneType().toString(),
                        "kind", kind(entry.getValue()),
                        "cost", entry.getValue().getPayCosts() == null ? "None"
                                : entry.getValue().getPayCosts().toSimpleString(),
                        "description", entry.getValue().getDescription())).toList()));
    }

    private static String name(Card card, Player player) {
        if (!card.getView().canBeShownTo(player.getView()) || !card.getView().canFaceDownBeShownTo(player.getView())) {
            return card.isFaceDown() ? "Face-down card" : "Hidden card";
        }
        return card.isFaceDown() ? card.getOriginalState(CardStateName.Original).getName() : card.getName();
    }

    private static String kind(SpellAbility ability) {
        if (ability.isLandAbility()) {
            return "Play land";
        }
        if (ability.isSpell()) {
            return "Cast spell";
        }
        if (ability.isManaAbility()) {
            return "Activate mana ability";
        }
        return ability.isActivatedAbility() ? "Activate ability" : "Special action";
    }
}
