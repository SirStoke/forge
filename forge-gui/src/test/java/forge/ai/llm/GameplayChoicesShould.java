package forge.ai.llm;

import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema.*;
import forge.ai.LobbyPlayerAi;
import forge.ai.llm.tools.SubmitChoice;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.*;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.cost.*;
import forge.game.event.GameEventGameFinished;
import forge.game.event.GameEventGameStarted;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetChoices;
import forge.game.trigger.TriggerHandler;
import forge.game.trigger.WrappedAbility;
import forge.game.ability.AbilityFactory;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.util.Lang;
import forge.util.Localizer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Exercises real engine mutations through MCP submissions, with only the language model mocked. */
public class GameplayChoicesShould {
    @BeforeClass
    public void initializeLanguage() {
        Localizer.getInstance().initialize("en-US", "res/languages/");
        Lang.createInstance("en-US");
    }

    private static final class Session implements AutoCloseable {
        final Game game;
        final Player player;
        final Player opponent;
        final AcpSyncClient client = mock(AcpSyncClient.class);
        final CodexAcpAgent agent;
        final PlayerControllerLlm controller;
        final McpSyncClient mcp;
        final List<String> prompts = new ArrayList<>();

        Session() {
            game = new Match(new GameRules(GameType.Constructed), List.of(
                    new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("One", null)),
                    new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("Two", null))), "LLM gameplay").createGame();
            player = game.getPlayers().get(0);
            opponent = game.getPlayers().get(1);
            player.setStartingLife(20);
            opponent.setStartingLife(20);
            game.setAge(GameStage.Play);
            game.getPhaseHandler().devModeSet(PhaseType.MAIN1, player, false, 1);
            AtomicReference<String> url = new AtomicReference<>();
            when(client.newSession(any())).thenAnswer(invocation -> {
                NewSessionRequest request = invocation.getArgument(0);
                url.set(((McpServerHttp) request.mcpServers().get(0)).url());
                return new NewSessionResponse("gameplay-test");
            });
            var model = SessionConfigSelect.builder().id("model").name("Model").currentValue(CodexAcpAgent.MODEL)
                    .options(List.of(new SessionConfigSelectOption(CodexAcpAgent.MODEL, CodexAcpAgent.MODEL))).build();
            when(client.setSessionConfigOption(any())).thenReturn(new SetSessionConfigOptionResponse(List.of(model)));
            agent = CodexAcpAgent.initialize(client, Path.of("."), player);
            controller = new PlayerControllerLlm(game, player, player.getLobbyPlayer(), () -> agent);
            player.dangerouslySetController(controller);
            game.fireEvent(new GameEventGameStarted(GameType.Constructed, player, game.getPlayers()));
            var transport = HttpClientStreamableHttpTransport.builder(URI.create(url.get()).resolve("/").toString()).endpoint("/mcp").build();
            mcp = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build();
            mcp.initialize();
        }

        Card card(Player owner, ZoneType zone, String name, String... script) {
            List<String> lines = new ArrayList<>(List.of("Name:" + name));
            lines.addAll(List.of(script));
            Card card = CardFactory.getCard(new PaperCard(CardRules.fromScript(lines), "", CardRarity.Common), owner, game);
            if (zone == ZoneType.Stack) game.getStackZone().add(card);
            else owner.getZone(zone).add(card);
            card.setSickness(false);
            return card;
        }

        Card creature(Player owner, String name) {
            return card(owner, ZoneType.Battlefield, name, "ManaCost:0", "Types:Creature", "PT:2/2", "Oracle:");
        }

        Card mountain(String name) {
            return card(player, ZoneType.Battlefield, name, "ManaCost:no cost", "Types:Basic Land Mountain", "Oracle:",
                    "A:AB$ Mana | Cost$ T | Produced$ R | SpellDescription$ Add {R}.");
        }

        String id(String prompt) {
            return prompt.lines().filter(line -> line.startsWith("Request ID: ")).findFirst().orElseThrow().substring(12);
        }

        int option(String prompt, String text) {
            return prompt.lines().filter(line -> line.matches("\\d+: .*"))
                    .filter(line -> line.contains(text)).mapToInt(line -> Integer.parseInt(line.substring(0, line.indexOf(':'))))
                    .findFirst().orElseThrow(() -> new AssertionError("Missing option " + text + " in " + prompt));
        }

        Map<String, Object> choices(String prompt, String... labels) {
            return Map.of("requestId", id(prompt), "choices", Arrays.stream(labels).map(label -> option(prompt, label)).toList());
        }

        void answer(Function<String, Map<String, Object>> script) {
            doAnswer(invocation -> {
                String prompt = ((PromptRequest) invocation.getArgument(0)).text();
                prompts.add(prompt);
                Assert.assertFalse(mcp.callTool(new CallToolRequest(SubmitChoice.NAME, script.apply(prompt))).isError(), prompt);
                return null;
            }).when(client).prompt(any());
        }

        @Override public void close() {
            mcp.close();
            game.fireEvent(new GameEventGameFinished());
        }
    }

    @Test
    public void castWithModelChosenTargetsAndManaSources() {
        try (Session s = new Session()) {
            Card first = s.mountain("First Mountain");
            Card second = s.mountain("Second Mountain");
            Card target = s.creature(s.opponent, "Chosen creature");
            Card bolt = s.card(s.player, ZoneType.Hand, "Lightning Bolt", "ManaCost:R", "Types:Instant", "Oracle:",
                    "A:SP$ DealDamage | ValidTgts$ Any | NumDmg$ 3 | SpellDescription$ Deal 3 damage to any target.");
            s.game.getAction().checkStaticAbilities();
            s.answer(prompt -> {
                if (prompt.contains("Decision: Choose targets")) return s.choices(prompt, "Chosen creature");
                if (prompt.contains("Activate mana ability:")) {
                    if (prompt.contains("Spend floating")) return s.choices(prompt, "Spend floating");
                    return s.choices(prompt, "Activate mana ability: Second Mountain");
                }
                if (prompt.contains("Spend floating")) return s.choices(prompt, "Spend floating");
                throw new AssertionError(prompt);
            });
            Assert.assertTrue(s.controller.playChosenSpellAbility(bolt.getSpellAbilities().get(0)));
            SpellAbility cast = s.game.getStack().peek().getSpellAbility();
            Assert.assertSame(cast.getTargets().getFirstTargetedCard(), target);
            Assert.assertTrue(second.isTapped());
            Assert.assertFalse(first.isTapped());
            Assert.assertEquals(cast.getPayingMana().size(), 1);
        }
    }

    @Test
    public void selectModesAndUseThoseModesDuringCasting() {
        try (Session s = new Session()) {
            Card charm = s.card(s.player, ZoneType.Hand, "Choice spell", "ManaCost:0", "Types:Instant", "Oracle:",
                    "A:SP$ Charm | Choices$ Draw,Life | CharmNum$ 1 | SpellDescription$ Choose one.",
                    "SVar:Draw:DB$ Draw | Defined$ You | NumCards$ 1 | SpellDescription$ Draw a card.",
                    "SVar:Life:DB$ GainLife | Defined$ You | LifeAmount$ 5 | SpellDescription$ Gain 5 life.");
            s.answer(prompt -> s.choices(prompt, "Gain 5 life"));
            Assert.assertTrue(s.controller.playChosenSpellAbility(charm.getSpellAbilities().get(0)));
            SpellAbility cast = s.game.getStack().peek().getSpellAbility();
            Assert.assertEquals(cast.getSubAbility().getApi(), forge.game.ability.ApiType.GainLife);
            Assert.assertEquals(s.prompts.size(), 1);
        }
    }

    @Test
    public void payAdditionalCostsUsingChosenCardsAndOrder() {
        try (Session s = new Session()) {
            Card keep = s.creature(s.player, "Keep creature");
            Card sacrifice = s.creature(s.player, "Sacrifice creature");
            Card keepInHand = s.card(s.player, ZoneType.Hand, "Keep in hand", "ManaCost:0", "Types:Artifact", "Oracle:");
            Card discard = s.card(s.player, ZoneType.Hand, "Discard me", "ManaCost:0", "Types:Artifact", "Oracle:");
            Card spell = s.card(s.player, ZoneType.Hand, "Cost spell", "ManaCost:0", "Types:Instant", "Oracle:",
                    "A:SP$ GainLife | Cost$ 0 Sac<1/Creature> Discard<1/Card> | Defined$ You | LifeAmount$ 1 | SpellDescription$ Gain 1 life.");
            s.answer(prompt -> {
                if (prompt.contains("Decision: Order cost")) {
                    List<Integer> order = prompt.lines().filter(line -> line.matches("\\d+: .*"))
                            .map(line -> Integer.parseInt(line.substring(0, line.indexOf(':')))).toList();
                    return Map.of("requestId", s.id(prompt), "choices", order);
                }
                if (prompt.contains("Sacrifice")) return s.choices(prompt, "Sacrifice creature id=");
                if (prompt.contains("Discard")) return s.choices(prompt, "Discard me id=");
                throw new AssertionError(prompt);
            });
            Assert.assertTrue(s.controller.playChosenSpellAbility(spell.getSpellAbilities().get(0)));
            Assert.assertTrue(sacrifice.isInZone(ZoneType.Graveyard));
            Assert.assertTrue(discard.isInZone(ZoneType.Graveyard));
            Assert.assertTrue(keep.isInZone(ZoneType.Battlefield));
            Assert.assertTrue(keepInHand.isInZone(ZoneType.Hand));
        }
    }

    @Test
    public void exposeOnlyOfferedSearchCardsAndHonorSingleAndMultipleSelections() {
        try (Session s = new Session()) {
            Card a = s.card(s.player, ZoneType.Library, "Search A", "ManaCost:0", "Types:Artifact", "Oracle:");
            Card b = s.card(s.player, ZoneType.Library, "Search B", "ManaCost:0", "Types:Artifact", "Oracle:");
            s.card(s.player, ZoneType.Library, "Not offered", "ManaCost:0", "Types:Artifact", "Oracle:");
            s.card(s.opponent, ZoneType.Hand, "Opponent secret", "ManaCost:0", "Types:Artifact", "Oracle:");
            Card source = s.card(s.player, ZoneType.Hand, "Search", "ManaCost:0", "Types:Instant", "Oracle:",
                    "A:SP$ ChangeZone | Origin$ Library | Destination$ Hand | ChangeType$ Artifact | SpellDescription$ Search.");
            SpellAbility ability = source.getSpellAbilities().get(0);
            ability.setActivatingPlayer(s.player);
            s.answer(prompt -> {
                Assert.assertFalse(prompt.contains("Not offered"));
                Assert.assertFalse(prompt.contains("Opponent secret"));
                return prompt.contains("Decision: Single") ? s.choices(prompt, "Search B") : s.choices(prompt, "Search B", "Search A");
            });
            CardCollection offered = new CardCollection(List.of(a, b));
            Assert.assertSame(s.controller.chooseSingleCardForZoneChange(ZoneType.Hand, List.of(ZoneType.Library), ability,
                    offered, null, "Single search", false, s.player), b);
            Assert.assertEquals(s.controller.chooseCardsForZoneChange(ZoneType.Hand, List.of(ZoneType.Library), ability,
                    offered, 2, 2, null, "Multiple search", s.player), List.of(b, a));
            Assert.assertTrue(s.controller.describe(a).startsWith("Hidden card"), "Search permission must end with its decision");
        }
    }

    @Test
    public void declareChosenAttackersAndBlockersAndRejectMenaceViolation() {
        try (Session s = new Session()) {
            Card attack = s.creature(s.player, "Attack me");
            Card stay = s.creature(s.player, "Stay home");
            Combat combat = new Combat(s.player);
            combat.initConstraints();
            s.answer(prompt -> s.choices(prompt, "Attack me id="));
            s.controller.declareAttackers(s.player, combat);
            Assert.assertTrue(CombatUtil.validateAttackers(combat));
            Assert.assertTrue(combat.isAttacking(attack));
            Assert.assertFalse(combat.isAttacking(stay));

            Card enemy = s.creature(s.opponent, "Menace attacker");
            enemy.addIntrinsicKeyword("Menace");
            Combat defense = new Combat(s.opponent);
            defense.addAttacker(enemy, s.player);
            int[] attempts = {0};
            s.answer(prompt -> ++attempts[0] == 1 ? s.choices(prompt, "Attack me id=") : s.choices(prompt, "Attack me id=", "Stay home id="));
            s.controller.declareBlockers(s.player, defense);
            Assert.assertNull(CombatUtil.validateBlocks(defense, s.player));
            Assert.assertEquals(defense.getBlockers(enemy).size(), 2);
            Assert.assertEquals(attempts[0], 2);
        }
    }

    @Test
    public void rejectMalformedStaleAndDuplicateChoicesWithoutConsumingPendingDecision() {
        try (Session s = new Session()) {
            Card a = s.creature(s.player, "A");
            Card b = s.creature(s.player, "B");
            AtomicReference<String> previous = new AtomicReference<>();
            doAnswer(invocation -> {
                String prompt = ((PromptRequest) invocation.getArgument(0)).text();
                String id = s.id(prompt);
                if (previous.get() != null) {
                    Assert.assertTrue(s.mcp.callTool(new CallToolRequest(SubmitChoice.NAME,
                            Map.of("requestId", previous.get(), "choices", List.of(0)))).isError());
                }
                for (List<Integer> invalid : List.of(List.<Integer>of(), List.of(0, 0), List.of(99))) {
                    Assert.assertTrue(s.mcp.callTool(new CallToolRequest(SubmitChoice.NAME,
                            Map.of("requestId", id, "choices", invalid))).isError());
                }
                Assert.assertFalse(s.mcp.callTool(new CallToolRequest(SubmitChoice.NAME,
                        Map.of("requestId", id, "choices", List.of(1)))).isError());
                previous.set(id);
                return null;
            }).when(s.client).prompt(any());
            for (int i = 0; i < 2; i++) {
                Assert.assertSame(s.controller.chooseCardsForEffect(new CardCollection(List.of(a, b)), null, "Choose one", 1, 1, false, null).get(0), b);
            }
        }
        for (Object invalid : List.of(1.5, Long.MAX_VALUE, "1", true)) {
            Assert.expectThrows(IllegalArgumentException.class, () -> SubmitChoice.compile(new CallToolRequest(SubmitChoice.NAME,
                    Map.of("requestId", "request", "choices", List.of(invalid)))));
        }
    }

    @Test
    public void cancelManaPaymentAndRestoreSpellAndPaidMana() {
        try (Session s = new Session()) {
            s.mountain("Mountain");
            Card spell = s.card(s.player, ZoneType.Hand, "Two mana", "ManaCost:1 R", "Types:Instant", "Oracle:",
                    "A:SP$ GainLife | Defined$ You | LifeAmount$ 1 | SpellDescription$ Gain 1 life.");
            s.answer(prompt -> {
                if (prompt.contains("Spend floating")) return s.choices(prompt, "Spend floating");
                if (prompt.contains("Activate mana ability:")) return s.choices(prompt, "Activate mana ability:");
                return Map.of("requestId", s.id(prompt), "choices", List.of());
            });
            Assert.assertFalse(s.controller.playChosenSpellAbility(spell.getSpellAbilities().get(0)));
            Assert.assertEquals(s.game.getStack().size(), 0);
            Assert.assertEquals(s.player.getCardsIn(ZoneType.Hand).size(), 1);
            Assert.assertFalse(s.player.getCardsIn(ZoneType.Battlefield).get(0).isTapped(), "Undoable mana ability should be refunded on cancellation");
        }
    }

    @Test
    public void chooseLifeInsteadOfManaAndRejectOutOfRangeNumbers() {
        try (Session s = new Session()) {
            Card spell = s.card(s.player, ZoneType.Hand, "Phyrexian", "ManaCost:R/P", "Types:Instant", "Oracle:",
                    "A:SP$ GainLife | Defined$ You | LifeAmount$ 1 | SpellDescription$ Gain 1 life.");
            s.answer(prompt -> s.choices(prompt, "Pay 2 life"));
            Assert.assertTrue(s.controller.playChosenSpellAbility(spell.getSpellAbilities().get(0)));
            Assert.assertEquals(s.player.getLife(), 18);
            doAnswer(invocation -> {
                String prompt = ((PromptRequest) invocation.getArgument(0)).text();
                Assert.assertTrue(s.mcp.callTool(new CallToolRequest(SubmitChoice.NAME, Map.of("requestId", s.id(prompt), "value", 9))).isError());
                Assert.assertFalse(s.mcp.callTool(new CallToolRequest(SubmitChoice.NAME, Map.of("requestId", s.id(prompt), "value", 3))).isError());
                return null;
            }).when(s.client).prompt(any());
            Assert.assertEquals(s.controller.chooseNumber(null, "Pick a number", 1, 5), 3);
        }
    }

    @Test
    public void selectCountersAndPreventSpeculativeOrSimulationPrompts() {
        try (Session s = new Session()) {
            Card card = s.creature(s.player, "Counter source");
            card.setCounters(CounterEnumType.P1P1, 3);
            Card other = s.creature(s.player, "Other source");
            other.setCounters(CounterEnumType.P1P1, 2);
            SpellAbility sa = new SpellAbility.EmptySa(forge.game.ability.ApiType.Cleanup, card);
            sa.setActivatingPlayer(s.player);
            CostRemoveAnyCounter cost = new CostRemoveAnyCounter("2", CounterEnumType.P1P1, "Creature.YouCtrl", "creatures", false);
            s.answer(prompt -> s.choices(prompt, "Other source", "Other source"));
            PaymentDecision payment = cost.accept(s.controller.getCostDecisionMaker(s.player, sa, false));
            Assert.assertNotNull(payment);
            Assert.assertEquals(payment.counterTable.get(null, other, CounterEnumType.P1P1), 2);
            Assert.assertEquals(payment.counterTable.get(null, card, CounterEnumType.P1P1), 0);
            clearInvocations(s.client);
            s.controller.locally(() -> s.controller.chooseNumber(null, "Probe", 0, 1));
            s.game.markAsSimulationCopy();
            s.controller.chooseNumber(null, "Simulation", 0, 1);
            verify(s.client, never()).prompt(any());
        }
    }

    @Test
    public void divideTargetedDamageAndPreserveAllocationsWhenRetargetingACopy() {
        try (Session s = new Session()) {
            Card a = s.creature(s.opponent, "Target A");
            Card b = s.creature(s.opponent, "Target B");
            Card c = s.creature(s.opponent, "Target C");
            Card spell = s.card(s.player, ZoneType.Hand, "Divided damage", "ManaCost:0", "Types:Instant", "Oracle:",
                    "A:SP$ DealDamage | ValidTgts$ Creature | TargetMin$ 1 | TargetMax$ 3 | NumDmg$ 3 | DividedAsYouChoose$ 3 | SpellDescription$ Divide 3 damage.");
            s.answer(prompt -> prompt.contains("Choose targets") ? s.choices(prompt, "Target A", "Target B")
                    : Map.of("requestId", s.id(prompt), "value", 1));
            Assert.assertTrue(s.controller.playChosenSpellAbility(spell.getSpellAbilities().get(0)));
            SpellAbility cast = s.game.getStack().peek().getSpellAbility();
            Assert.assertEquals(cast.getDividedValue(a), Integer.valueOf(1));
            Assert.assertEquals(cast.getDividedValue(b), Integer.valueOf(2));
            SpellAbility copy = cast.copy();
            copy.setCopied(true);
            s.answer(prompt -> s.choices(prompt, "Target B", "Target C"));
            TargetChoices newTargets = s.controller.chooseNewTargetsFor(copy, target -> true, false);
            Assert.assertEquals(newTargets, List.of(b, c));
            Assert.assertEquals(newTargets.getDividedValue(b), Integer.valueOf(1));
            Assert.assertEquals(newTargets.getDividedValue(c), Integer.valueOf(2));
            Assert.assertEquals(cast.getTargets(), List.of(a, b), "Retargeting a copy must not mutate the original spell");
        }
    }

    @Test
    public void chooseSpellsOnTheStackAndImmediateTriggerTargets() {
        try (Session s = new Session()) {
            Card spell = s.card(s.opponent, ZoneType.Stack, "Opponent spell", "ManaCost:0", "Types:Instant", "Oracle:",
                    "A:SP$ GainLife | Defined$ You | LifeAmount$ 1 | SpellDescription$ Gain 1 life.");
            SpellAbility opposing = spell.getSpellAbilities().get(0);
            opposing.setActivatingPlayer(s.opponent);
            s.game.getStack().add(opposing);
            Card counter = s.card(s.player, ZoneType.Hand, "Counter", "ManaCost:0", "Types:Instant", "Oracle:",
                    "A:SP$ Counter | ValidTgts$ Spell | SpellDescription$ Counter target spell.");
            s.answer(prompt -> s.choices(prompt, "Opponent spell"));
            Assert.assertTrue(s.controller.playChosenSpellAbility(counter.getSpellAbilities().get(0)));
            Assert.assertSame(s.game.getStack().peek().getSpellAbility().getTargets().getFirstTargetedSpell(), opposing);

            Card host = s.creature(s.player, "Trigger source");
            Card target = s.creature(s.opponent, "Trigger target");
            var trigger = TriggerHandler.parseTrigger("Mode$ Always | TriggerDescription$ Deal damage.", host, true);
            SpellAbility effect = AbilityFactory.getAbility("DB$ DealDamage | ValidTgts$ Creature | NumDmg$ 1 | SpellDescription$ Deal 1 damage.", host);
            effect.setActivatingPlayer(s.player);
            WrappedAbility wrapped = new WrappedAbility(trigger, effect, null);
            s.answer(prompt -> s.choices(prompt, "Trigger target"));
            Assert.assertTrue(s.controller.playTrigger(host, wrapped, true));
            Assert.assertEquals(target.getDamage(), 1);
        }
    }

    @Test
    public void enforceEvidenceAndVariableExileCostsAndAllowCancellation() {
        try (Session s = new Session()) {
            Card low = s.card(s.player, ZoneType.Graveyard, "Low value", "ManaCost:1", "Types:Artifact", "Oracle:");
            Card high = s.card(s.player, ZoneType.Graveyard, "High value", "ManaCost:3", "Types:Artifact", "Oracle:");
            Card host = s.creature(s.player, "Cost source");
            SpellAbility sa = new SpellAbility.EmptySa(forge.game.ability.ApiType.Cleanup, host);
            sa.setActivatingPlayer(s.player);
            CostCollectEvidence evidence = new CostCollectEvidence("3");
            s.answer(prompt -> Map.of("requestId", s.id(prompt), "choices", List.of()));
            Assert.assertNull(evidence.accept(s.controller.getCostDecisionMaker(s.player, sa, false)), "Cancel must not pay zero evidence");
            int[] calls = {0};
            s.answer(prompt -> ++calls[0] == 1 ? s.choices(prompt, "Low value") : s.choices(prompt, "High value"));
            PaymentDecision paid = evidence.accept(s.controller.getCostDecisionMaker(s.player, sa, false));
            Assert.assertEquals(paid.cards, new CardCollection(high));
            Assert.assertEquals(calls[0], 2);
            CostExile exile = new CostExile("1+", "Card+withTotalCMCGE3", null, ZoneType.Graveyard);
            s.answer(prompt -> s.choices(prompt, "High value"));
            paid = exile.accept(s.controller.getCostDecisionMaker(s.player, sa, false));
            Assert.assertEquals(paid.cards, new CardCollection(high));
            Assert.assertTrue(low.isInZone(ZoneType.Graveyard), "Choices are not paid until the cost engine applies them");
        }
    }

    @Test
    public void chooseConvokePaymentsAndAllocateLegalCombatDamage() {
        try (Session s = new Session()) {
            Card convoker = s.card(s.player, ZoneType.Battlefield, "Convoker", "ManaCost:R", "Types:Creature", "PT:2/2", "Oracle:");
            Card host = s.creature(s.player, "Attacker");
            host.addIntrinsicKeyword("Trample");
            SpellAbility sa = new SpellAbility.EmptySa(forge.game.ability.ApiType.Cleanup, host);
            sa.setActivatingPlayer(s.player);
            s.answer(prompt -> s.choices(prompt, "Convoker"));
            Map<Card, forge.card.mana.ManaCostShard> convoke = s.controller.chooseCardsForConvokeOrImprovise(sa,
                    new forge.card.mana.ManaCost("R"), new CardCollection(convoker), false, true, null);
            Assert.assertEquals(convoke.get(convoker), forge.card.mana.ManaCostShard.RED);
            Card a = s.creature(s.opponent, "Blocker A");
            Card b = s.creature(s.opponent, "Blocker B");
            s.answer(prompt -> Map.of("requestId", s.id(prompt), "value", 2));
            Map<Card, Integer> damage = s.controller.assignCombatDamage(host, new CardCollection(List.of(a, b)), null, 5, s.opponent, false);
            Assert.assertEquals(damage.get(a), Integer.valueOf(2));
            Assert.assertEquals(damage.get(b), Integer.valueOf(2));
            Assert.assertEquals(damage.get(null), Integer.valueOf(1));
        }
    }

    @Test
    public void chooseTheColorProducedByAManaSource() {
        try (Session s = new Session()) {
            s.card(s.player, ZoneType.Battlefield, "Color source", "ManaCost:no cost", "Types:Land", "Oracle:",
                    "A:AB$ Mana | Cost$ T | Produced$ Combo R U | SpellDescription$ Add red or blue.");
            Card spell = s.card(s.player, ZoneType.Hand, "Generic spell", "ManaCost:1", "Types:Artifact", "Oracle:");
            s.answer(prompt -> {
                if (prompt.contains("Choose color")) return s.choices(prompt, ": 2");
                return prompt.contains("Spend floating") ? s.choices(prompt, "Spend floating") : s.choices(prompt, "Activate mana ability");
            });
            Assert.assertTrue(s.controller.playChosenSpellAbility(spell.getSpellAbilities().get(0)));
            Assert.assertEquals(s.game.getStack().peek().getSpellAbility().getPayingMana().get(0).getColor(), forge.card.MagicColor.BLUE);
        }
    }
}
