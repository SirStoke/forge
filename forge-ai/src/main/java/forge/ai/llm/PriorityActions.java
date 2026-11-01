package forge.ai.llm;

import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilMana;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds priority actions on the game thread without using the AI's strategic spell selection. */
final class PriorityActions {
    private PriorityActions() {
    }

    static Map<PriorityDecision.PlayAction, SpellAbility> available(Player player) {
        final Map<PriorityDecision.PlayAction, SpellAbility> options = new LinkedHashMap<>();
        final CardCollection cards = new CardCollection(player.getCardsIn(ZoneType.Hand));
        cards.addAll(player.getGame().getCardsIn(ZoneType.Battlefield));
        // Forge's external-zone view includes graveyard, exile, command, sideboard,
        // permitted library tops and opponents' cards with play/look permission.
        cards.addAll(player.getCardsActivatableInExternalZones(true));
        for (Card card : cards) {
            for (SpellAbility ability : card.getAllPossibleAbilities(player, true)) {
                if (ability.getPayCosts() != null && ability.getPayCosts().hasManaCost()
                        && !ComputerUtilMana.canPayManaCost(ability, player, 0, false)) {
                    continue;
                }
                if (ComputerUtilAbility.isFullyTargetable(ability)) {
                    options.put(new PriorityDecision.PlayAction(card.getId(), ability.getId()), ability);
                }
            }
        }
        return Collections.unmodifiableMap(options);
    }
}
