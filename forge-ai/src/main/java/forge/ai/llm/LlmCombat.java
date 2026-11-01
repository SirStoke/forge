package forge.ai.llm;

import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CounterEnumType;
import forge.game.keyword.Keyword;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.player.Player;

import java.util.*;
import java.util.function.Supplier;

/** Validates complete declarations, including requirements involving multiple creatures. */
final class LlmCombat {
    private LlmCombat() { }

    private record Attack(Card attacker, GameEntity defender, String label) {
        @Override public String toString() { return label; }
    }

    private record Block(Card blocker, Card attacker, String label) {
        @Override public String toString() { return label; }
    }

    static void attack(LlmDecisionController controller, Player player, Combat combat) {
        List<Attack> options = new ArrayList<>();
        for (Card card : player.getCreaturesInPlay()) {
            for (GameEntity defender : combat.getDefenders()) {
                if (CombatUtil.canAttack(card, defender)) {
                    options.add(new Attack(card, defender, controller.describe(card) + " attacks " + controller.describe(defender)));
                }
            }
        }
        Map<Card, GameEntity> before = new LinkedHashMap<>(combat.getAttackersAndDefenders());
        List<Attack> chosen = controller.select("Declare attackers and their defenders. Choose at most one defender per attacker. "
                        + "Satisfy must-attack requirements and restrictions such as attacking alone or together.",
                options, 0, options.size(), false, selected -> {
                    if (selected.stream().map(Attack::attacker).distinct().count() != selected.size()) return false;
                    combat.clearAttackers();
                    try {
                        for (Attack attack : selected) {
                            if (!CombatUtil.canAttack(attack.attacker(), attack.defender())) return false;
                            combat.addAttacker(attack.attacker(), attack.defender());
                        }
                        return CombatUtil.validateAttackers(combat);
                    } finally {
                        combat.clearAttackers();
                        before.forEach(combat::addAttacker);
                    }
                }, () -> null);
        combat.clearAttackers();
        if (chosen == null) {
            // The rules solver supplies a legal declaration if the harness cannot produce one.
            combat.getAttackConstraints().getLegalAttackers().getLeft().forEach(combat::addAttacker);
        } else {
            chosen.forEach(a -> combat.addAttacker(a.attacker(), a.defender()));
        }
    }

    static boolean block(LlmDecisionController controller, Player player, Combat combat) {
        List<Block> options = new ArrayList<>();
        for (Card blocker : player.getCreaturesInPlay()) {
            for (Card attacker : combat.getAttackers()) {
                if (CombatUtil.canBlock(attacker, blocker) && CombatUtil.canBeBlocked(attacker, combat, player)) {
                    options.add(new Block(blocker, attacker, controller.describe(blocker) + " blocks " + controller.describe(attacker)));
                }
            }
        }
        List<Block> before = new ArrayList<>();
        for (Card blocker : combat.getAllBlockers()) {
            if (blocker.getController() == player) {
                for (Card attacker : combat.getAttackersBlockedBy(blocker)) before.add(new Block(blocker, attacker, ""));
            }
        }
        List<Block> chosen = controller.select("Declare blockers. Choose blocker/attacker pairs. "
                        + "Respect flying, reach, menace, lure, must-block requirements and each blocker's capacity.",
                options, 0, options.size(), false, selected -> {
                    clearBlocks(player, combat);
                    try {
                        for (Block block : selected) {
                            combat.addBlocker(block.attacker(), block.blocker());
                        }
                        // Check each pair in the context of the complete declaration. In particular,
                        // a multi-blocker may satisfy lure and also block another attacker.
                        for (Block block : selected) {
                            combat.removeBlockAssignment(block.attacker(), block.blocker());
                            boolean legal = CombatUtil.canBlock(block.attacker(), block.blocker(), combat);
                            combat.addBlocker(block.attacker(), block.blocker());
                            if (!legal) return false;
                        }
                        return CombatUtil.validateBlocks(combat, player) == null;
                    } finally {
                        clearBlocks(player, combat);
                        before.forEach(b -> combat.addBlocker(b.attacker(), b.blocker()));
                    }
                }, () -> null);
        if (chosen == null) return false;
        clearBlocks(player, combat);
        chosen.forEach(b -> combat.addBlocker(b.attacker(), b.blocker()));
        return true;
    }

    private static void clearBlocks(Player player, Combat combat) {
        for (Card blocker : new ArrayList<>(combat.getAllBlockers())) {
            if (blocker.getController() == player) combat.undoBlockingAssignment(blocker);
        }
    }

    static Map<Card, Integer> damage(LlmDecisionController controller, Card attacker, CardCollectionView blockers,
            CardCollectionView remaining, int total, GameEntity defender, boolean overrideOrder,
            Supplier<Map<Card, Integer>> fallback) {
        boolean divideFreely = overrideOrder && attacker.hasKeyword("You may assign CARDNAME's combat damage divided as you choose among "
                + "defending player and/or any number of creatures they control.");
        boolean toDefender = defender != null && (attacker.hasKeyword(Keyword.TRAMPLE)
                || attacker.hasKeyword("Trample:Planeswalker") || divideFreely);
        List<Card> targets = new ArrayList<>(blockers);
        if (toDefender || targets.isEmpty()) targets.add(null);
        if (remaining != null && remaining.size() > 1 && attacker.isAttacking()
                && controller.yes("Defer assigning damage for " + controller.describe(attacker) + " until another combatant?", () -> false)) return null;
        for (int attempt = 0; attempt < 3; attempt++) {
            Map<Card, Integer> assignments = new LinkedHashMap<>();
            int left = total;
            for (int i = 0; i < targets.size(); i++) {
                Card target = targets.get(i);
                final int available = left;
                int amount = i == targets.size() - 1 ? left : controller.number("Assign combat damage from "
                        + controller.describe(attacker) + " to " + controller.describe(target == null ? defender : target)
                        + ". Total=" + total + " remaining=" + left + ". "
                        + (overrideOrder ? "You may ignore combatant order." : "Lethal damage must precede later recipients.")
                        + (attempt == 0 ? "" : " Previous assignment was illegal; satisfy lethal damage requirements."), 0, left, () -> available);
                assignments.put(target, amount);
                left -= amount;
            }
            boolean legal = true;
            for (int i = 0; i < blockers.size(); i++) {
                Card blocker = blockers.get(i);
                int lethal = blocker.isPlaneswalker() ? blocker.getCounters(CounterEnumType.LOYALTY) : Math.max(0, blocker.getLethalDamage());
                if (attacker.hasKeyword(Keyword.DEATHTOUCH) && blocker.isCreature()) lethal = Math.min(1, lethal);
                if (assignments.getOrDefault(blocker, 0) < lethal) {
                    if (!overrideOrder) {
                        for (int j = i + 1; j < targets.size(); j++) if (assignments.getOrDefault(targets.get(j), 0) > 0) legal = false;
                    }
                    if (toDefender && !divideFreely && assignments.getOrDefault(null, 0) > 0) legal = false;
                }
            }
            if (legal) return assignments;
        }
        return fallback.get();
    }
}
