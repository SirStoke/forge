package forge.ai.llm;

import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilMana;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds hand-play options on the game thread without using the AI's strategic spell selection. */
final class HandPlayOptions {
    private HandPlayOptions() {
    }

    static Map<PriorityDecision.PlayCard, SpellAbility> available(Player player) {
        final Map<PriorityDecision.PlayCard, SpellAbility> options = new LinkedHashMap<>();
        for (Card card : player.getCardsIn(ZoneType.Hand)) {
            for (SpellAbility ability : card.getAllPossibleAbilities(player, true)) {
                if (!(ability.isSpell() || ability.isLandAbility())) {
                    continue;
                }
                if (ability.getPayCosts() != null && ability.getPayCosts().hasManaCost()
                        && !ComputerUtilMana.canPayManaCost(ability, player, 0, false)) {
                    continue;
                }
                if (ComputerUtilAbility.isFullyTargetable(ability)) {
                    options.put(new PriorityDecision.PlayCard(card.getId(), ability.getId()), ability);
                }
            }
        }
        return Collections.unmodifiableMap(options);
    }
}
