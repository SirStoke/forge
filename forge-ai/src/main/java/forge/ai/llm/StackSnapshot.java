package forge.ai.llm;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;

import java.util.ArrayList;
import java.util.List;

/** Immutable player-visible stack data, captured on the game thread in resolution order. */
public record StackSnapshot(List<Item> items) {
    public StackSnapshot {
        items = List.copyOf(items);
    }

    static StackSnapshot capture(Player viewer) {
        final List<Item> items = new ArrayList<>();
        for (SpellAbilityStackInstance instance : viewer.getGame().getStack()) {
            final Card source = instance.getSourceCard();
            final List<Target> targets = new ArrayList<>();
            String description = visible(source, viewer) ? instance.getStackDescription() : "Ability of a hidden card";
            if (!visible(source, viewer) && instance.isSpell()) {
                description = "Face-down spell";
            }
            for (SpellAbilityStackInstance part = instance; part != null; part = part.getSubInstance()) {
                for (var target : part.getTargetChoices()) {
                    if (target instanceof Card card) {
                        targets.add(new Target("card", card.getId(), name(card, viewer)));
                        if (!visible(card, viewer)) {
                            description = redact(description, card);
                        }
                    } else if (target instanceof Player player) {
                        targets.add(new Target("player", player.getId(), player.getName()));
                    } else if (target instanceof SpellAbility ability) {
                        final var stackItem = viewer.getGame().getStack().getInstanceMatchingSpellAbilityID(ability);
                        targets.add(new Target(stackItem == null ? "ability" : "stack_item",
                                stackItem == null ? ability.getId() : stackItem.getId(), name(ability.getHostCard(), viewer)));
                        if (!visible(ability.getHostCard(), viewer)) {
                            description = redact(description, ability.getHostCard());
                        }
                    }
                }
            }
            final Player controller = instance.getActivatingPlayer();
            final String kind = instance.isSpell() ? "spell"
                    : instance.isTrigger() ? "triggered_ability" : "ability";
            items.add(new Item(instance.getId(), kind,
                    new Source(source.getId(), name(source, viewer), source.isFaceDown()),
                    controller == null ? null : new Controller(controller.getId(), controller.getName()),
                    description, instance.getSpellAbility().isCopied(), List.copyOf(targets)));
        }
        return new StackSnapshot(items);
    }

    private static boolean visible(Card card, Player viewer) {
        return card.getView().canBeShownTo(viewer.getView()) && card.getView().canFaceDownBeShownTo(viewer.getView());
    }

    private static String name(Card card, Player viewer) {
        if (!visible(card, viewer)) {
            return card.isFaceDown() ? "Face-down card" : "Hidden card";
        }
        return card.isFaceDown() ? card.getOriginalState(forge.card.CardStateName.Original).getName() : card.getName();
    }

    private static String redact(String description, Card card) {
        for (String name : List.of(card.getName(), card.getOriginalState(forge.card.CardStateName.Original).getName())) {
            if (!name.isEmpty()) {
                description = description.replace(name, "Hidden card");
            }
        }
        return description;
    }

    public record Item(int id, String kind, Source source, Controller controller, String description,
                       boolean copied, List<Target> targets) {
    }

    public record Source(int id, String name, boolean faceDown) {
    }

    public record Controller(int id, String name) {
    }

    public record Target(String kind, int id, String name) {
    }
}
