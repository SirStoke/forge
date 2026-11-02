package forge.ai.llm;

import forge.game.GameObject;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetChoices;
import forge.game.card.Card;
import forge.game.staticability.StaticAbilityMustTarget;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Builds legal targeting steps without calling the AI's private spell preparation. */
final class LlmTargetSelection {
    private LlmTargetSelection() { }

    static boolean choose(LlmDecisionController controller, SpellAbility sa, Predicate<GameObject> filter,
                          Integer exactCount, List<Integer> divisions) {
        if (!sa.usesTargeting()) return true;
        TargetChoices saved = sa.getTargets();
        sa.clearTargets();
        TargetChoices empty = sa.getTargets();
        List<GameObject> options = new ArrayList<>(sa.getTargetRestrictions().getAllCandidates(sa));
        if (sa.getTargetRestrictions().getZone().contains(ZoneType.Stack)) {
            options.removeIf(option -> option instanceof Card card && card.isInZone(ZoneType.Stack));
            for (var entry : sa.getHostCard().getGame().getStack()) {
                var spell = entry.getSpellAbility();
                if (sa.canTargetSpellAbility(spell)) options.add(spell);
            }
        }
        options.removeIf(filter.negate());
        int min = exactCount == null ? sa.getMinTargets() : exactCount;
        int max = exactCount == null ? sa.getMaxTargets() : exactCount;
        if (sa.isDividedAsYouChoose() && divisions == null) max = Math.min(max, sa.getStillToDivide());
        if (min > options.size() || max < min) {
            sa.setTargets(saved);
            return false;
        }
        if (max == 0 || options.isEmpty()) return sa.isTargetNumberValid();
        int steps = 0;
        for (SpellAbility step = sa.getRootAbility(); step != null; step = step.getSubAbility()) if (step.usesTargeting()) steps++;
        final boolean onlyTargetingStep = steps == 1;
        final Predicate<List<GameObject>> legal = chosen -> {
            sa.setTargets(new TargetChoices());
            try {
                for (GameObject target : chosen) {
                    if (!canTarget(sa, target)) return false;
                    sa.getTargets().add(target);
                }
                return sa.isTargetNumberValid() && (!onlyTargetingStep || StaticAbilityMustTarget.meetsMustTargetRestriction(sa.getRootAbility()));
            } finally {
                sa.setTargets(empty);
            }
        };
        List<GameObject> selected;
        if (sa.getTargetRestrictions().isRandomTarget() && exactCount == null) {
            // Random targeting is a rule outcome, not a strategic model choice.
            selected = new ArrayList<>();
            int count = sa.getTargetRestrictions().isRandomNumTargets()
                    ? MyRandom.getRandom().nextInt(Math.max(1, min), Math.min(max, options.size()) + 1) : min;
            while (selected.size() < count && !options.isEmpty()) {
                GameObject next = options.remove(MyRandom.getRandom().nextInt(options.size()));
                List<GameObject> candidate = new ArrayList<>(selected);
                candidate.add(next);
                sa.setTargets(new TargetChoices());
                boolean valid = true;
                for (GameObject target : candidate) {
                    if (!canTarget(sa, target)) { valid = false; break; }
                    sa.getTargets().add(target);
                }
                sa.setTargets(empty);
                if (valid) selected.add(next);
            }
        } else {
            selected = controller.selectCancelable(controller.context(sa, "Choose targets; each option may be chosen once. "
                            + "Targets must satisfy all restrictions: " + sa.getTargetRestrictions().getValidDesc()),
                    options, min, max, false, legal, () -> null);
        }
        if (selected == null || !legal.test(selected)) {
            sa.setTargets(saved);
            return false;
        }
        sa.setTargets(new TargetChoices());
        selected.forEach(sa.getTargets()::add);
        if (sa.isDividedAsYouChoose() && !selected.isEmpty()) {
            int remaining = sa.getStillToDivide();
            if (divisions == null && sa.hasParam("DividedUpTo")) {
                remaining = controller.number("Choose total amount to divide", selected.size(), remaining, () -> selected.size());
            }
            for (int i = 0; i < selected.size(); i++) {
                int amount;
                if (divisions != null) {
                    amount = divisions.get(i);
                } else {
                    int maxAmount = remaining - (selected.size() - i - 1);
                    final int defaultAmount = maxAmount;
                    amount = i == selected.size() - 1 ? remaining : controller.number(
                            controller.context(sa, "Allocate to " + controller.describe(selected.get(i))),
                            1, maxAmount, () -> defaultAmount);
                    remaining -= amount;
                }
                sa.addDividedAllocation(selected.get(i), amount);
            }
        }
        return true;
    }

    private static boolean canTarget(SpellAbility sa, GameObject target) {
        return target instanceof SpellAbility spell ? sa.canTargetSpellAbility(spell) : sa.canTarget(target);
    }
}
