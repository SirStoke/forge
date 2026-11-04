package forge.ai.llm;

import forge.ai.AiCostDecision;
import forge.ai.PlayerControllerAi;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.player.DelayedReveal;
import forge.game.cost.CostDecisionMakerBase;
import forge.game.cost.CostSacrifice;
import forge.game.cost.PaymentDecision;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.player.PlaySpellAbility;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

/** Deterministic reference lines verify that fixture/rules bugs cannot be mistaken for model failures. No ACP agent. */
public class PuzzleFixtureChecks extends PuzzleEvalSupport {
    private abstract static class ReferenceController extends PlayerControllerAi {
        ReferenceController(Scenario s) {
            super(s.game, s.player, s.player.getLobbyPlayer());
            s.player.dangerouslySetController(this);
        }

        Card find(Player owner, String name) {
            return owner.getCreaturesInPlay().stream().filter(c -> c.getName().equals(name)).findFirst().orElseThrow();
        }

        @Override public boolean playChosenSpellAbility(SpellAbility ability) {
            final boolean played = PlaySpellAbility.playSpellAbility(this, player, ability);
            Assert.assertTrue(played, "Reference action failed: " + ability.getHostCard().getName() + ": " + ability);
            return played;
        }

        @Override public boolean playTrigger(Card host, WrappedAbility ability, boolean mandatory) {
            return PlaySpellAbility.playSpellAbilityNoStack(this, player, ability, false);
        }

        @Override public boolean playSaFromPlayEffect(SpellAbility ability) {
            return PlaySpellAbility.playSpellAbility(this, player, ability);
        }

        @Override public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String title,
                List<String> options, Card show, Map<String, Object> params) { return true; }

        @Override public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> options, SpellAbility sa,
                String title, Map<String, Object> params) { return options.get(0); }
    }

    @Test
    public void winWithAmuletBouncelandAndAHastyTitan() {
        try (Scenario s = AmuletTitanEval.position()) {
            new ReferenceController(s) {
                final ArrayDeque<String> actions = new ArrayDeque<>(List.of(
                        "Simic Growth Chamber", "Simic Growth Chamber", "Primeval Titan", "Hanweir Battlements"));

                @Override public List<SpellAbility> chooseSpellAbilityToPlay() {
                    // Float GU after the Amulet untap resolves, before the bounce resolves.
                    for (Card land : player.getCardsIn(ZoneType.Battlefield)) {
                        if (land.getName().equals("Simic Growth Chamber") && !land.isTapped()) {
                            return List.of(land.getManaAbilities().get(0));
                        }
                    }
                    if (!s.game.getStack().isEmpty() || s.game.getStack().hasSimultaneousStackEntries() || actions.isEmpty()) return null;
                    String next = actions.removeFirst();
                    return List.of(PriorityActions.available(player).values().stream()
                            .filter(sa -> sa.getHostCard().getName().equals(next))
                            .filter(sa -> !next.equals("Hanweir Battlements") || sa.getApi() == ApiType.Pump)
                            .findFirst().orElseThrow(() -> new AssertionError("Reference action unavailable: " + next)));
                }

                @Override public void orderAndPlaySimultaneousSa(List<SpellAbility> abilities) {
                    // Stack the bounce first so Amulet's untap resolves first.
                    var ordered = new java.util.ArrayList<>(abilities);
                    ordered.sort(java.util.Comparator.comparing(sa -> sa.getApi() == ApiType.Untap));
                    for (SpellAbility sa : ordered) {
                        Assert.assertTrue(PlaySpellAbility.playSpellAbility(this, player, sa));
                    }
                }

                @Override public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin,
                        SpellAbility sa, CardCollection options, DelayedReveal reveal, String title,
                        boolean optional, Player decider) {
                    if (destination == ZoneType.Hand && sa.getHostCard().getName().equals("Simic Growth Chamber")) {
                        return options.stream().filter(c -> c.getName().equals("Simic Growth Chamber")).findFirst().orElseThrow();
                    }
                    if (sa.getHostCard().getName().equals("Primeval Titan")) {
                        return options.stream().filter(c -> c.getName().equals("Hanweir Battlements")
                                || c.getName().equals("Crumbling Vestige")).findFirst().orElse(null);
                    }
                    return super.chooseSingleCardForZoneChange(destination, origin, sa, options, reveal, title, optional, decider);
                }

                @Override public byte chooseColor(String title, SpellAbility sa, ColorSet colors) {
                    return sa.getHostCard().getName().equals("Crumbling Vestige")
                            ? MagicColor.RED : super.chooseColor(title, sa, colors);
                }

                @Override public boolean chooseTargetsFor(SpellAbility ability) {
                    if (ability.getHostCard().getName().equals("Hanweir Battlements") && ability.usesTargeting()) {
                        ability.resetTargets();
                        ability.getTargets().add(find(player, "Primeval Titan"));
                        return true;
                    }
                    return super.chooseTargetsFor(ability);
                }

                @Override public void declareAttackers(Player attacker, Combat combat) {
                    combat.addAttacker(find(player, "Primeval Titan"), s.opponent);
                }
            };
            s.run(() -> s.game.getPhaseHandler().getTurn() > 1 || s.game.getPhaseHandler().is(PhaseType.CLEANUP));
            AmuletTitanEval.assertTitanLethal(s);
        }
    }

    @Test
    public void winThePitUsingPostBlockSacrificesAndTheRealUpkeepTrigger() {
        try (Scenario s = WelcomeToThePitEval.position()) {
            new ReferenceController(s) {
                final Card vampire = find(s.player, "Bloodthrone Vampire");
                final ArrayDeque<Card> sacrifices = new ArrayDeque<>(s.player.getCreaturesInPlay().stream()
                        .filter(c -> c != vampire).toList());

                @Override public void declareBlockers(Player defender, Combat combat) {
                    Card colossus = find(s.opponent, "Colossus of Sardia");
                    combat.addBlocker(colossus, vampire);
                    var blockers = s.player.getCreaturesInPlay().stream().filter(c -> c != vampire && !c.isTapped()).iterator();
                    for (Card attacker : combat.getAttackers()) if (attacker != colossus) combat.addBlocker(attacker, blockers.next());
                }

                @Override public List<SpellAbility> chooseSpellAbilityToPlay() {
                    return sacrifices.isEmpty() ? null : List.of(vampire.getSpellAbilities().stream()
                            .filter(sa -> sa.getApi() == ApiType.Pump).findFirst().orElseThrow());
                }

                @Override public CostDecisionMakerBase getCostDecisionMaker(Player payer, SpellAbility ability, boolean effect, String title) {
                    return new AiCostDecision(payer, ability, effect) {
                        @Override public PaymentDecision visit(CostSacrifice cost) {
                            return PaymentDecision.card(sacrifices.removeFirst());
                        }
                    };
                }
            };
            s.declareBlocks();
            s.run(() -> s.game.getPhaseHandler().getTurn() > 2 || s.game.getPhaseHandler().is(PhaseType.DRAW, s.player));
            Assert.assertEquals(s.player.getOutcome().altWinSourceName, "Mortal Combat");
        }
    }

    @Test
    public void winDestinyUsingIndexCascadeOracleAndTheWish() {
        try (Scenario s = MasterYourDestinyEval.position()) {
            new ReferenceController(s) {
                final ArrayDeque<String> actions = new ArrayDeque<>(List.of("Index", "Island", "Bloodbraid Elf", "Djinn of Wishes"));

                @Override public List<SpellAbility> chooseSpellAbilityToPlay() {
                    if (!s.game.getStack().isEmpty() || s.game.getStack().hasSimultaneousStackEntries() || actions.isEmpty()) return null;
                    String next = actions.removeFirst();
                    return List.of(PriorityActions.available(player).values().stream()
                            .filter(sa -> sa.getHostCard().getName().equals(next))
                            .filter(sa -> !next.equals("Djinn of Wishes") || sa.getApi() == ApiType.PeekAndReveal)
                            .findFirst().orElseThrow(() -> new AssertionError("Reference action unavailable: " + next)));
                }

                @Override public CardCollectionView orderMoveToZoneList(CardCollectionView options, ZoneType destination, SpellAbility sa) {
                    if (sa != null && sa.getApi() == ApiType.RearrangeTopOfLibrary) {
                        return new CardCollection(List.of("Erratic Explosion", "Tranquility", "Bogardan Hellkite", "Island", "Coiling Oracle")
                                .stream().map(name -> options.stream().filter(c -> c.getName().equals(name)).findFirst().orElseThrow()).toList());
                    }
                    return super.orderMoveToZoneList(options, destination, sa);
                }

                @Override public boolean chooseTargetsFor(SpellAbility ability) {
                    SpellAbility sa = ability instanceof WrappedAbility wrapped ? wrapped.getWrappedAbility() : ability;
                    if (sa.getHostCard().getName().equals("Bogardan Hellkite") && sa.usesTargeting()) {
                        Card elemental = find(s.opponent, "Air Elemental");
                        Card servant = find(s.opponent, "Illusionary Servant");
                        sa.resetTargets();
                        sa.getTargets().add(elemental);
                        sa.getTargets().add(servant);
                        sa.addDividedAllocation(elemental, 4);
                        sa.addDividedAllocation(servant, 1);
                        return true;
                    }
                    return super.chooseTargetsFor(ability);
                }

                @Override public void declareAttackers(Player attacker, Combat combat) {
                    combat.addAttacker(find(s.player, "Bloodbraid Elf"), s.opponent);
                }
            };
            s.run(() -> s.game.getPhaseHandler().getTurn() > 1 || s.game.getPhaseHandler().is(PhaseType.CLEANUP));
        }
    }
}
