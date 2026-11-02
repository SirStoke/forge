package forge.ai.llm;

import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCostShard;
import forge.game.GameActionUtil;
import forge.game.card.Card;
import forge.game.mana.Mana;
import forge.game.mana.ManaConversionMatrix;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.PlaySpellAbility;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.*;

/** Interactive mana payment using the same rules/execution path as the human controller. */
final class LlmManaPayment {
    private LlmManaPayment() { }

    private record Action(Mana mana, SpellAbility ability, boolean life, ManaCostShard lifeShard, String label) {
        @Override public String toString() { return label; }
    }

    static boolean pay(LlmDecisionController controller, ManaCostBeingPaid cost, SpellAbility sa,
                       ManaConversionMatrix matrix, boolean effect) {
        var player = controller.getPlayer();
        var pool = player.getManaPool();
        var oldMatrix = new ManaConversionMatrix();
        oldMatrix.restoreColorReplacements();
        oldMatrix.applyCardMatrix(pool);
        if (matrix != null) pool.applyCardMatrix(matrix);
        var previousCost = sa.getManaCostBeingPaid();
        player.pushPaidForSA(sa);
        sa.setManaCostBeingPaid(cost);
        Set<SpellAbility> failed = new HashSet<>();
        int lifeToPay = 0;
        try {
            for (int step = 0; step < 256 && !cost.isPaid(); step++) {
                List<Action> options = new ArrayList<>();
                for (Mana mana : pool) {
                    if (mana.meetsManaRestrictions(sa) && sa.allowsPayingWithShard(mana.getSourceCard(), mana.getColor())
                            && cost.isNeeded(mana, pool)) {
                        options.add(new Action(mana, null, false, null, "Spend floating " + mana + " from " + controller.describe(mana.getSourceCard())));
                    }
                }
                Set<Card> cards = new LinkedHashSet<>(player.getGame().getCardsIn(ZoneType.Battlefield));
                cards.addAll(player.getCardsIn(ZoneType.Hand));
                cards.addAll(player.getCardsIn(ZoneType.Graveyard));
                cards.addAll(player.getCardsIn(ZoneType.Exile));
                cards.addAll(player.getCardsIn(ZoneType.Command));
                cards.addAll(player.getCardsActivatableInExternalZones(true));
                for (Card card : cards) {
                    for (SpellAbility base : card.getManaAbilities()) {
                        List<SpellAbility> abilities = new ArrayList<>();
                        abilities.add(base);
                        abilities.addAll(GameActionUtil.getAlternativeCosts(base, player, false));
                        for (SpellAbility ability : abilities) {
                            ability.setActivatingPlayer(player);
                            if (ability != sa && !failed.contains(ability) && ability.canPlay(true)) {
                                options.add(new Action(null, ability, false, null, "Activate mana ability: " + controller.describe(ability)));
                            }
                        }
                    }
                }
                if (cost.containsPhyrexianMana() && player.canPayLife(lifeToPay + 2, effect, sa)) {
                    options.add(new Action(null, null, true, null, "Pay 2 life for a Phyrexian mana symbol"));
                }
                if (player.hasKeyword("PayLifeInsteadOf:B") && cost.hasAnyKind(ManaAtom.BLACK)
                        && player.canPayLife(lifeToPay + 2, effect, sa)) {
                    options.add(new Action(null, null, true, ManaCostShard.BLACK, "Pay 2 life instead of black mana"));
                }
                Action action = controller.oneCancelable(controller.context(sa, "Pay remaining mana " + cost
                        + ". Choose a source, floating mana, or life payment. Choose no options to cancel."), options, true, () -> null);
                if (action == null) return false;
                if (action.mana() != null) {
                    if (!pool.tryPayCostWithMana(sa, cost, action.mana(), false)) return false;
                    sa.getPayingMana().add(action.mana());
                } else if (action.life()) {
                    if (action.lifeShard() != null) {
                        cost.decreaseShard(action.lifeShard(), 1);
                    } else {
                        if (!cost.payPhyrexian()) return false;
                        sa.setSpendPhyrexianMana(true);
                    }
                    lifeToPay += 2;
                } else {
                    SpellAbility ability = action.ability();
                    // Express choices are hints used by the heuristic auto-payer. Leaving one here
                    // can bypass the controller's color/combo choices or carry over a previous color.
                    ability.getAllManaParts().forEach(part -> part.setExpressChoice(""));
                    if (!PlaySpellAbility.playSpellAbility(controller, player, ability)) {
                        failed.add(ability);
                    } else {
                        sa.getPayingManaAbilities().add(ability);
                    }
                    if (controller.actionWasCanceled()) return false;
                }
            }
            if (!cost.isPaid()) return false;
            if (lifeToPay > 0) player.payLife(lifeToPay, sa, effect);
            return true;
        } finally {
            sa.setManaCostBeingPaid(previousCost);
            player.popPaidForSA();
            pool.restoreColorReplacements();
            pool.applyCardMatrix(oldMatrix);
        }
    }
}
