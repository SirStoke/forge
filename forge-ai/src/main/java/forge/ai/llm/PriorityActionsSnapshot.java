package forge.ai.llm;

import forge.card.CardStateName;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.List;
import java.util.Map;

/** Immutable priority options captured on the game thread; costs have not been checked for affordability. */
public record PriorityActionsSnapshot(String requestId, boolean affordabilityChecked, List<Action> actions) {
    public PriorityActionsSnapshot {
        actions = List.copyOf(actions);
    }

    static PriorityActionsSnapshot capture(Player player, String requestId,
                                           Map<PriorityDecision.PlayAction, SpellAbility> options) {
        return new PriorityActionsSnapshot(requestId, false, options.entrySet().stream().map(entry -> {
            final SpellAbility ability = entry.getValue();
            return new Action(entry.getKey().cardId(), entry.getKey().abilityId(), name(ability.getHostCard(), player),
                    ability.getHostCard().getZone().getZoneType().toString(), kind(ability),
                    ability.getPayCosts() == null ? "None" : ability.getPayCosts().toSimpleString(),
                    ability.getDescription());
        }).toList());
    }

    private static String name(Card card, Player player) {
        if (!card.getView().canBeShownTo(player.getView()) || !card.getView().canFaceDownBeShownTo(player.getView())) {
            return card.isFaceDown() ? "Face-down card" : "Hidden card";
        }
        return card.isFaceDown() ? card.getOriginalState(CardStateName.Original).getName() : card.getName();
    }

    private static String kind(SpellAbility ability) {
        if (ability.isLandAbility()) return "Play land";
        if (ability.isSpell()) return "Cast spell";
        if (ability.isManaAbility()) return "Activate mana ability";
        return ability.isActivatedAbility() ? "Activate ability" : "Special action";
    }

    public record Action(int cardId, int abilityId, String name, String zone, String kind,
                         String cost, String description) { }
}
