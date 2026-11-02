package forge.ai.llm;

import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCostShard;
import forge.game.GameActionUtil;
import forge.ai.llm.tools.PayMana;
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
            int executed = 0;
            for (int round = 0; round < PayMana.MAX_STEPS && !cost.isPaid(); round++) {
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
                ChoiceDecision decision = controller.requestManaPayment(sa, "Pay remaining mana " + cost
                                + ". Choose sources, floating mana, or life payments. Choose no options to cancel.",
                        options.stream().map(Action::label).toList(), options.stream().map(option -> option.ability() != null).toList());
                if (decision == null || decision instanceof ChoiceDecision.Cancel) return false;
                final boolean bulk = decision instanceof ChoiceDecision.Payment;
                final List<PayMana.Step> plan;
                if (decision instanceof ChoiceDecision.Payment payment) {
                    plan = payment.plan().steps();
                } else {
                    var answer = ((ChoiceDecision.Choose) decision).answer();
                    if (answer.choices().isEmpty()) return false;
                    plan = List.of(new PayMana.Step(answer.choices().get(0), List.of()));
                }
                for (PayMana.Step step : plan) {
                    // A plan is an ordered intention, not an affordability guarantee. Recheck
                    // each resource against the current state and never tap unneeded sources.
                    if (cost.isPaid()) break;
                    if (++executed > PayMana.MAX_STEPS) return false;
                    Action action = options.get(step.option());
                    if (action.mana() != null) {
                        if (!eligible(action.mana(), sa, cost, pool)
                                || !pool.tryPayCostWithExactMana(sa, cost, action.mana())) {
                            paused(controller, sa, action);
                            break;
                        }
                        sa.getPayingMana().add(action.mana());
                    } else if (action.life()) {
                        if (!player.canPayLife(lifeToPay + 2, effect, sa)
                                || (action.lifeShard() == null ? !cost.containsPhyrexianMana() : !cost.hasAnyKind(ManaAtom.BLACK))) {
                            paused(controller, sa, action);
                            break;
                        }
                        if (action.lifeShard() != null) {
                            cost.decreaseShard(action.lifeShard(), 1);
                        } else {
                            if (!cost.payPhyrexian()) return false;
                            sa.setSpendPhyrexianMana(true);
                        }
                        lifeToPay += 2;
                    } else {
                        SpellAbility ability = action.ability();
                        if (!ability.canPlay(true)) {
                            paused(controller, sa, action);
                            break;
                        }
                        // Do not reuse the heuristic auto-payer's stale color hints. Planned
                        // colors go through controller callbacks and their actual legal menus.
                        ability.getAllManaParts().forEach(part -> part.setExpressChoice(""));
                        final Map<Mana, Integer> before = new IdentityHashMap<>();
                        if (bulk) for (Mana mana : pool) before.merge(mana, 1, Integer::sum);
                        boolean played = controller.withManaColors(ability, step.colors(),
                                () -> PlaySpellAbility.playSpellAbility(controller, player, ability));
                        if (controller.actionWasCanceled()) return false;
                        if (!played) {
                            failed.add(ability);
                            paused(controller, sa, action);
                            break;
                        }
                        sa.getPayingManaAbilities().add(ability);
                        if (bulk) {
                            // Mana can use one shared object for several units. Count by identity
                            // so old pool mana is not swept into the automatic payment.
                            List<Mana> produced = new ArrayList<>();
                            for (Mana mana : pool) {
                                int oldCount = before.getOrDefault(mana, 0);
                                if (oldCount > 0) before.put(mana, oldCount - 1);
                                else produced.add(mana);
                            }
                            for (Mana mana : produced) {
                                if (cost.isPaid()) break;
                                if (eligible(mana, sa, cost, pool) && pool.tryPayCostWithExactMana(sa, cost, mana)) {
                                    sa.getPayingMana().add(mana);
                                }
                            }
                        }
                    }
                }
            }
            if (!cost.isPaid()) return false;
            if (lifeToPay > 0) {
                if (!player.canPayLife(lifeToPay, effect, sa)) return false;
                player.payLife(lifeToPay, sa, effect);
            }
            return true;
        } finally {
            sa.setManaCostBeingPaid(previousCost);
            player.popPaidForSA();
            pool.restoreColorReplacements();
            pool.applyCardMatrix(oldMatrix);
        }
    }

    private static boolean eligible(Mana mana, SpellAbility sa, ManaCostBeingPaid cost, forge.game.mana.ManaPool pool) {
        boolean present = false;
        for (Mana candidate : pool) if (candidate == mana) { present = true; break; }
        return present && mana.meetsManaRestrictions(sa)
                && sa.allowsPayingWithShard(mana.getSourceCard(), mana.getColor()) && cost.isNeeded(mana, pool);
    }

    private static void paused(LlmDecisionController controller, SpellAbility sa, Action action) {
        controller.notifyOfValue(sa, null, "Payment plan paused because a step is unavailable: " + action.label()
                + ". Choose a revised plan or cancel_action when offered.");
    }
}
