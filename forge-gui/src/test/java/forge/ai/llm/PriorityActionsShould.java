package forge.ai.llm;

import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema.McpServerHttp;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionConfigSelect;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionConfigSelectOption;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionConfigOptionResponse;
import forge.ai.LobbyPlayerAi;
import forge.ai.llm.tools.PassPriority;
import forge.ai.llm.tools.PlayAction;
import forge.ai.llm.tools.SubmitChoice;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.event.GameEventGameFinished;
import forge.game.event.GameEventGameStarted;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
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
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PriorityActionsShould {
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

        Session() {
            final var players = List.of(
                    new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("One", null)),
                    new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("Two", null)));
            game = new Match(new GameRules(GameType.Constructed), players, "Priority actions").createGame();
            player = game.getPlayers().get(0);
            opponent = game.getPlayers().get(1);
            player.setStartingLife(20);
            opponent.setStartingLife(20);
            game.setAge(GameStage.Play);
            game.getPhaseHandler().devModeSet(PhaseType.MAIN1, player, false, 1);
            final var url = new AtomicReference<String>();
            when(client.newSession(any())).thenAnswer(invocation -> {
                final NewSessionRequest request = invocation.getArgument(0);
                url.set(((McpServerHttp) request.mcpServers().get(0)).url());
                return new NewSessionResponse("test-session");
            });
            final var model = SessionConfigSelect.builder().id("model").name("Model")
                    .currentValue(CodexAcpAgent.MODEL)
                    .options(List.of(new SessionConfigSelectOption(CodexAcpAgent.MODEL, CodexAcpAgent.MODEL))).build();
            when(client.setSessionConfigOption(any())).thenReturn(new SetSessionConfigOptionResponse(List.of(model)));
            agent = CodexAcpAgent.initialize(client, Path.of("."), player);
            controller = new PlayerControllerLlm(game, player, player.getLobbyPlayer(), () -> agent);
            player.dangerouslySetController(controller);
            game.fireEvent(new GameEventGameStarted(GameType.Constructed, player, game.getPlayers()));
            final var transport = HttpClientStreamableHttpTransport
                    .builder(URI.create(url.get()).resolve("/").toString()).endpoint("/mcp").build();
            mcp = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build();
            mcp.initialize();
        }

        Card card(Player owner, ZoneType zone, String name, String... script) {
            final List<String> lines = new ArrayList<>(List.of("Name:" + name));
            lines.addAll(List.of(script));
            final var paper = new PaperCard(CardRules.fromScript(lines), "", CardRarity.Common);
            final Card card = CardFactory.getCard(paper, owner, game);
            owner.getZone(zone).add(card);
            return card;
        }

        CallToolRequest play(String requestId, int cardId, int abilityId) {
            return new CallToolRequest(PlayAction.NAME,
                    Map.of("requestId", requestId, "cardId", cardId, "abilityId", abilityId));
        }

        SpellAbility choose(Card card) {
            when(client.prompt(any())).thenAnswer(invocation -> {
                final PromptRequest prompt = invocation.getArgument(0);
                final var action = Pattern.compile("cardId=" + card.getId() + ", abilityId=(\\d+)")
                        .matcher(prompt.text());
                Assert.assertTrue(action.find(), "Card must be offered in the priority prompt: " + prompt.text());
                final String requestId = prompt.text().lines().filter(line -> line.startsWith("Request ID: "))
                        .findFirst().orElseThrow().substring("Request ID: ".length());
                Assert.assertFalse(mcp.callTool(play(requestId, card.getId(), Integer.parseInt(action.group(1)))).isError());
                return null;
            });
            final var selected = controller.chooseSpellAbilityToPlay();
            Assert.assertNotNull(selected);
            Assert.assertEquals(selected.size(), 1);
            Assert.assertSame(selected.get(0).getHostCard(), card);
            return selected.get(0);
        }

        @Override
        public void close() {
            mcp.close();
            game.fireEvent(new GameEventGameFinished());
        }
    }

    private String requestId(PromptRequest prompt) {
        return prompt.text().lines().filter(line -> line.startsWith("Request ID: "))
                .findFirst().orElseThrow().substring("Request ID: ".length());
    }

    @Test
    public void selectOneOfTwoIdenticalLandsAndExecuteOnlyOnTheGameThread() {
        try (var session = new Session()) {
            final Card first = session.card(session.player, ZoneType.Hand, "Forest",
                    "ManaCost:no cost", "Types:Basic Land Forest", "Oracle:({T}: Add {G}.)");
            final Card second = session.card(session.player, ZoneType.Hand, "Forest",
                    "ManaCost:no cost", "Types:Basic Land Forest", "Oracle:({T}: Add {G}.)");
            final int abilityId = second.getSpellAbilities().stream().filter(a -> a.isLandAbility())
                    .findFirst().orElseThrow().getId();
            final Card opponentCard = session.card(session.opponent, ZoneType.Hand, "Opponent secret",
                    "ManaCost:0", "Types:Artifact", "Oracle:");
            when(session.client.prompt(any())).thenAnswer(invocation -> {
                final PromptRequest prompt = invocation.getArgument(0);
                final String id = requestId(prompt);
                Assert.assertTrue(prompt.text().contains("cardId=" + second.getId() + ", abilityId=" + abilityId));
                Assert.assertFalse(prompt.text().contains("Opponent secret"));
                Assert.assertTrue(session.mcp.callTool(session.play(id, opponentCard.getId(), abilityId)).isError());
                Assert.assertTrue(session.mcp.callTool(session.play(id, second.getId(), Integer.MAX_VALUE)).isError());
                Assert.assertFalse(session.mcp.callTool(session.play(id, second.getId(), abilityId)).isError());
                Assert.assertTrue(session.mcp.callTool(new CallToolRequest(PassPriority.NAME,
                        Map.of("requestId", id))).isError());
                Assert.assertTrue(second.isInZone(ZoneType.Hand), "The MCP thread must not execute the play");
                return null;
            });
            final var selected = session.controller.chooseSpellAbilityToPlay();
            Assert.assertNotNull(selected);
            Assert.assertEquals(selected.size(), 1);
            Assert.assertSame(selected.get(0).getHostCard(), second);
            Assert.assertTrue(session.controller.playChosenSpellAbility(selected.get(0)));
            Assert.assertTrue(second.isInZone(ZoneType.Battlefield));
            Assert.assertTrue(first.isInZone(ZoneType.Hand));
            Assert.assertNull(session.controller.chooseSpellAbilityToPlay(), "The land drop has been used");
        }
    }

    @Test
    public void castLightningBoltAndChooseItsTargetAfterSelectingTheSpell() {
        try (var session = new Session()) {
            // Declare the mana ability explicitly: this fixture does not load Forge's global card-type catalog.
            session.card(session.player, ZoneType.Battlefield, "Mountain",
                    "ManaCost:no cost", "Types:Basic Land Mountain", "Oracle:({T}: Add {R}.)",
                    "A:AB$ Mana | Cost$ T | Produced$ R | SpellDescription$ Add {R}.");
            final Card bolt = session.card(session.player, ZoneType.Hand, "Lightning Bolt",
                    "ManaCost:R", "Types:Instant",
                    "A:SP$ DealDamage | ValidTgts$ Any | NumDmg$ 3 | SpellDescription$ CARDNAME deals 3 damage to any target.",
                    "Oracle:Lightning Bolt deals 3 damage to any target.");
            final int abilityId = bolt.getSpellAbilities().get(0).getId();
            session.game.getAction().checkStaticAbilities();
            when(session.client.prompt(any())).thenAnswer(invocation -> {
                final String id = requestId(invocation.getArgument(0));
                Assert.assertFalse(session.mcp.callTool(session.play(id, bolt.getId(), abilityId)).isError());
                return null;
            });
            final var selected = session.controller.chooseSpellAbilityToPlay();
            Assert.assertNotNull(selected);
            Assert.assertTrue(selected.get(0).getTargets().isEmpty());
            doAnswer(invocation -> {
                final PromptRequest prompt = invocation.getArgument(0);
                final String preferred = prompt.text().contains("Choose targets") ? "Two id="
                        : prompt.text().contains("Spend floating") ? "Spend floating" : "Activate mana ability:";
                final int index = prompt.text().lines().filter(line -> line.matches("\\d+: .*"))
                        .filter(line -> line.contains(preferred))
                        .mapToInt(line -> Integer.parseInt(line.substring(0, line.indexOf(':')))).findFirst().orElseThrow();
                Assert.assertFalse(session.mcp.callTool(new CallToolRequest(SubmitChoice.NAME,
                        Map.of("requestId", requestId(prompt), "choices", List.of(index)))).isError());
                return null;
            }).when(session.client).prompt(any());
            Assert.assertTrue(session.controller.playChosenSpellAbility(selected.get(0)));
            Assert.assertEquals(session.game.getStack().size(), 1);
            final var cast = session.game.getStack().peek().getSpellAbility();
            Assert.assertEquals(cast.getHostCard().getName(), "Lightning Bolt");
            Assert.assertTrue(cast.isTargetNumberValid());
            Assert.assertTrue(cast.getTargets().contains(session.opponent));
        }
    }

    @Test
    public void activateBattlefieldAbilityWithAutomaticTargetingAndPayment() {
        try (var session = new Session()) {
            final Card source = session.card(session.player, ZoneType.Battlefield, "Damage artifact",
                    "ManaCost:0", "Types:Artifact", "Oracle:",
                    "A:AB$ DealDamage | Cost$ T | ValidTgts$ Opponent | NumDmg$ 1 | SpellDescription$ Deal 1 damage to target opponent.");
            final SpellAbility ability = session.choose(source);
            Assert.assertTrue(ability.isActivatedAbility());
            Assert.assertFalse(source.isTapped(), "Selecting the action must not execute it on the MCP thread");
            Assert.assertTrue(session.controller.playChosenSpellAbility(ability));
            Assert.assertTrue(source.isTapped());
            Assert.assertTrue(source.isInZone(ZoneType.Battlefield));
            Assert.assertEquals(session.game.getStack().size(), 1);
            Assert.assertTrue(session.game.getStack().peek().getSpellAbility().getTargets().contains(session.opponent));
        }
    }

    @Test
    public void activateManaAbilityImmediatelyWithoutPuttingItOnTheStack() {
        try (var session = new Session()) {
            final Card source = session.card(session.player, ZoneType.Battlefield, "Mana artifact",
                    "ManaCost:0", "Types:Artifact", "Oracle:",
                    "A:AB$ Mana | Cost$ T | Produced$ R | SpellDescription$ Add {R}.");
            final SpellAbility ability = session.choose(source);
            Assert.assertTrue(ability.isManaAbility());
            Assert.assertTrue(session.controller.playChosenSpellAbility(ability));
            Assert.assertTrue(source.isTapped());
            Assert.assertEquals(session.player.getManaPool().totalMana(), 1);
            Assert.assertTrue(session.game.getStack().isEmpty());
            Assert.assertTrue(PriorityActions.available(session.player).isEmpty(), "A tapped source cannot activate again");
        }
    }

    @Test
    public void offerHandActivationsAlongsideCastingOptions() {
        try (var session = new Session()) {
            final Card card = session.card(session.player, ZoneType.Hand, "Hand activation",
                    "ManaCost:5", "Types:Artifact", "Oracle:",
                    "A:AB$ GainLife | Cost$ 0 | ActivationZone$ Hand | LifeAmount$ 1 | SpellDescription$ Gain 1 life.");
            final SpellAbility ability = session.choose(card);
            Assert.assertTrue(ability.isActivatedAbility());
            Assert.assertTrue(session.controller.playChosenSpellAbility(ability));
            Assert.assertTrue(card.isInZone(ZoneType.Hand));
            Assert.assertEquals(session.game.getStack().size(), 1);
        }
    }

    @Test
    public void turnAMorphFaceUpAsASpecialActionWithoutUsingTheStack() {
        try (var session = new Session()) {
            final Card card = session.card(session.player, ZoneType.Battlefield, "Morph creature",
                    "ManaCost:5", "Types:Creature", "PT:3/3", "Oracle:", "K:Morph:0");
            card.turnFaceDown(true);
            Assert.assertTrue(PriorityPrompt.render(session.player, "request", PriorityActions.available(session.player))
                    .contains("Morph creature [Battlefield; Special action; cost:"));
            final SpellAbility ability = session.choose(card);
            Assert.assertTrue(ability.isTurnFaceUp());
            Assert.assertTrue(card.isFaceDown());
            Assert.assertTrue(session.controller.playChosenSpellAbility(ability));
            Assert.assertFalse(card.isFaceDown());
            Assert.assertTrue(session.game.getStack().isEmpty());
        }
    }

    @DataProvider
    public Object[][] externalZones() {
        return new Object[][] { { ZoneType.Graveyard }, { ZoneType.Exile }, { ZoneType.Command },
                { ZoneType.Library }, { ZoneType.Sideboard } };
    }

    @Test(dataProvider = "externalZones")
    public void castSpellsWithPermissionFromExternalZones(ZoneType zone) {
        try (var session = new Session()) {
            final Card card = session.card(session.player, zone, "External spell",
                    "ManaCost:0", "Types:Instant", "Oracle:",
                    "A:SP$ GainLife | ActivationZone$ " + zone + " | LifeAmount$ 1 | SpellDescription$ Gain 1 life.");
            final Card forbidden = session.card(session.player, zone, "Forbidden external spell",
                    "ManaCost:0", "Types:Instant", "Oracle:",
                    "A:SP$ GainLife | LifeAmount$ 1 | SpellDescription$ Gain 1 life.");
            final SpellAbility ability = session.choose(card);
            final var options = PriorityActions.available(session.player);
            Assert.assertFalse(options.values().stream().anyMatch(sa -> sa.getHostCard() == forbidden));
            final String prompt = PriorityPrompt.render(session.player, "request", options);
            Assert.assertTrue(prompt.contains("[" + zone + "; Cast spell; cost:"));
            Assert.assertFalse(prompt.contains("Forbidden external spell"));
            Assert.assertTrue(session.controller.playChosenSpellAbility(ability));
            Assert.assertTrue(card.isInZone(ZoneType.Stack));
            Assert.assertEquals(session.game.getStack().size(), 1);
        }
    }

    @Test
    public void offerFlashbackAndExcludeHiddenCardsAndUnusableAbilities() {
        try (var session = new Session()) {
            final Card flashback = session.card(session.player, ZoneType.Graveyard, "Flashback spell",
                    "ManaCost:5", "Types:Instant", "Oracle:", "K:Flashback:0",
                    "A:SP$ GainLife | LifeAmount$ 1 | SpellDescription$ Gain 1 life.");
            session.card(session.opponent, ZoneType.Hand, "Secret hand", "ManaCost:0", "Types:Artifact", "Oracle:");
            session.card(session.player, ZoneType.Library, "Secret library", "ManaCost:0", "Types:Artifact", "Oracle:");
            session.card(session.opponent, ZoneType.Battlefield, "Opponent ability",
                    "ManaCost:0", "Types:Artifact", "Oracle:",
                    "A:AB$ GainLife | Cost$ 0 | LifeAmount$ 1 | SpellDescription$ Gain 1 life.");
            session.card(session.player, ZoneType.Battlefield, "Unaffordable activation",
                    "ManaCost:0", "Types:Artifact", "Oracle:",
                    "A:AB$ GainLife | Cost$ 5 | LifeAmount$ 1 | SpellDescription$ Gain 1 life.");
            session.card(session.player, ZoneType.Battlefield, "No legal target",
                    "ManaCost:0", "Types:Artifact", "Oracle:",
                    "A:AB$ Destroy | Cost$ 0 | ValidTgts$ Creature | SpellDescription$ Destroy target creature.");
            final var options = PriorityActions.available(session.player);
            Assert.assertEquals(options.size(), 1);
            Assert.assertTrue(options.values().iterator().next().isFlashback());
            final String prompt = PriorityPrompt.render(session.player, "request", options);
            Assert.assertTrue(prompt.contains("Flashback spell [Graveyard; Cast spell; cost:"));
            for (String absent : List.of("Secret hand", "Secret library", "Opponent ability",
                    "Unaffordable activation", "No legal target")) {
                Assert.assertFalse(prompt.contains(absent), absent);
            }
            final SpellAbility ability = session.choose(flashback);
            Assert.assertTrue(ability.isFlashback());
            Assert.assertTrue(session.controller.playChosenSpellAbility(ability));
            Assert.assertTrue(flashback.isInZone(ZoneType.Stack));
        }
    }

    @Test
    public void respectPlayPermissionsForLibraryTopsAndOpponentsExiledCards() {
        try (var session = new Session()) {
            session.card(session.player, ZoneType.Battlefield, "Permission artifact",
                    "ManaCost:0", "Types:Artifact", "Oracle:",
                    "S:Mode$ Continuous | Affected$ Card.TopLibrary+YouOwn | AffectedZone$ Library | MayPlay$ True",
                    "S:Mode$ Continuous | Affected$ Card.OppOwn | AffectedZone$ Exile | MayPlay$ True");
            final Card top = session.card(session.player, ZoneType.Library, "Permitted top",
                    "ManaCost:0", "Types:Artifact", "Oracle:");
            session.card(session.player, ZoneType.Library, "Hidden second card",
                    "ManaCost:0", "Types:Artifact", "Oracle:");
            final Card exiled = session.card(session.opponent, ZoneType.Exile, "Permitted opponent card",
                    "ManaCost:0", "Types:Artifact", "Oracle:");
            session.game.getAction().checkStaticAbilities();
            final var options = PriorityActions.available(session.player);
            Assert.assertTrue(options.values().stream().anyMatch(sa -> sa.getHostCard() == top));
            Assert.assertTrue(options.values().stream().anyMatch(sa -> sa.getHostCard() == exiled));
            Assert.assertFalse(PriorityPrompt.render(session.player, "request", options).contains("Hidden second card"));
            final SpellAbility ability = session.choose(exiled);
            Assert.assertTrue(session.controller.playChosenSpellAbility(ability));
            Assert.assertTrue(exiled.isInZone(ZoneType.Stack));
            Assert.assertSame(session.game.getStack().peek().getActivatingPlayer(), session.player);
        }
    }

    @Test
    public void playLandFromTheGraveyardWhenPermissionAllowsIt() {
        try (var session = new Session()) {
            session.card(session.player, ZoneType.Battlefield, "Land permission",
                    "ManaCost:0", "Types:Artifact", "Oracle:",
                    "S:Mode$ Continuous | Affected$ Land.YouOwn | AffectedZone$ Graveyard | MayPlay$ True");
            final Card land = session.card(session.player, ZoneType.Graveyard, "Forest",
                    "ManaCost:no cost", "Types:Basic Land Forest", "Oracle:");
            session.game.getAction().checkStaticAbilities();
            final SpellAbility ability = session.choose(land);
            Assert.assertTrue(ability.isLandAbility());
            Assert.assertTrue(session.controller.playChosenSpellAbility(ability));
            Assert.assertTrue(land.isInZone(ZoneType.Battlefield));
            Assert.assertEquals(session.player.getLandsPlayedThisTurn(), 1);
        }
    }

    @Test
    public void shareOneDecisionAcrossPlayAndPassAndPreserveAcceptedChoicesAfterAcpFailure() {
        try (var session = new Session()) {
            final Card card = session.card(session.player, ZoneType.Hand, "Zero artifact",
                    "ManaCost:0", "Types:Artifact", "Oracle:");
            final int abilityId = card.getSpellAbilities().get(0).getId();
            final var previousId = new AtomicReference<String>();
            when(session.client.prompt(any())).thenAnswer(invocation -> {
                final String id = requestId(invocation.getArgument(0));
                final String old = previousId.getAndSet(id);
                if (old == null) {
                    Assert.assertFalse(session.mcp.callTool(new CallToolRequest(PassPriority.NAME,
                            Map.of("requestId", id))).isError());
                    Assert.assertTrue(session.mcp.callTool(session.play(id, card.getId(), abilityId)).isError());
                    return null;
                }
                Assert.assertTrue(session.mcp.callTool(session.play(old, card.getId(), abilityId)).isError());
                Assert.assertFalse(session.mcp.callTool(session.play(id, card.getId(), abilityId)).isError());
                throw new IllegalStateException("ACP failed after accepting the play");
            });
            Assert.assertNull(session.controller.chooseSpellAbilityToPlay());
            final var selected = session.controller.chooseSpellAbilityToPlay();
            Assert.assertNotNull(selected);
            Assert.assertSame(selected.get(0).getHostCard(), card);
            Assert.assertTrue(session.controller.playChosenSpellAbility(selected.get(0)));
            Assert.assertEquals(session.game.getStack().size(), 1);
        }
    }

    @Test
    public void distinguishCastingOptionsAndExcludePlaysWithWrongTimingOrInsufficientMana() {
        try (var session = new Session()) {
            final Card instant = session.card(session.player, ZoneType.Hand, "Two options",
                    "ManaCost:0", "Types:Instant", "Oracle:",
                    "A:SP$ Draw | Defined$ You | NumCards$ 1 | SpellDescription$ Draw a card.",
                    "A:SP$ GainLife | Defined$ You | LifeAmount$ 1 | SpellDescription$ Gain 1 life.");
            session.card(session.player, ZoneType.Hand, "Unaffordable spell", "ManaCost:R", "Types:Instant",
                    "A:SP$ Draw | Defined$ You | NumCards$ 1 | SpellDescription$ Draw a card.", "Oracle:");
            session.card(session.player, ZoneType.Hand, "Sorcery speed", "ManaCost:0", "Types:Sorcery",
                    "A:SP$ Draw | Defined$ You | NumCards$ 1 | SpellDescription$ Draw a card.", "Oracle:");
            session.card(session.player, ZoneType.Hand, "Forest", "ManaCost:no cost", "Types:Basic Land Forest", "Oracle:");
            session.game.getPhaseHandler().devModeSet(PhaseType.MAIN1, session.opponent, false, 1);
            session.game.getPhaseHandler().setPriority(session.player);
            final int firstId = instant.getSpellAbilities().get(0).getId();
            final int secondId = instant.getSpellAbilities().get(1).getId();
            when(session.client.prompt(any())).thenAnswer(invocation -> {
                final PromptRequest prompt = invocation.getArgument(0);
                Assert.assertTrue(prompt.text().contains("abilityId=" + firstId));
                Assert.assertTrue(prompt.text().contains("abilityId=" + secondId));
                Assert.assertFalse(prompt.text().contains("Unaffordable spell"));
                Assert.assertFalse(prompt.text().contains("Sorcery speed"));
                Assert.assertFalse(prompt.text().contains("Forest"));
                Assert.assertFalse(session.mcp.callTool(session.play(requestId(prompt), instant.getId(), secondId)).isError());
                return null;
            });
            final var selected = session.controller.chooseSpellAbilityToPlay();
            Assert.assertNotNull(selected);
            Assert.assertEquals(selected.get(0).getId(), secondId);
            Assert.assertTrue(session.controller.playChosenSpellAbility(selected.get(0)));
            Assert.assertEquals(session.game.getStack().peek().getSpellAbility().getApi(), forge.game.ability.ApiType.GainLife);
        }
    }

    @Test
    public void passAndCancelTheRequestWhenNoDecisionIsSubmittedOrPromptingFails() {
        try (var session = new Session()) {
            session.card(session.player, ZoneType.Hand, "Zero artifact", "ManaCost:0", "Types:Artifact", "Oracle:");
            final var lastId = new AtomicReference<String>();
            when(session.client.prompt(any())).thenAnswer(invocation -> {
                lastId.set(requestId(invocation.getArgument(0)));
                return null;
            });
            Assert.assertNull(session.controller.chooseSpellAbilityToPlay());
            Assert.expectThrows(IllegalStateException.class,
                    () -> session.agent.priorityDecision().call(lastId.get(), new PriorityDecision.Pass()));
            doAnswer(invocation -> {
                lastId.set(requestId(invocation.getArgument(0)));
                throw new IllegalStateException("Agent unavailable");
            }).when(session.client).prompt(any());
            Assert.assertNull(session.controller.chooseSpellAbilityToPlay());
            Assert.expectThrows(IllegalStateException.class,
                    () -> session.agent.priorityDecision().call(lastId.get(), new PriorityDecision.Pass()));
            session.game.markAsSimulationCopy();
            org.mockito.Mockito.clearInvocations(session.client);
            Assert.assertNotNull(session.controller.chooseSpellAbilityToPlay(), "Simulation copies use local AI");
            org.mockito.Mockito.verify(session.client, org.mockito.Mockito.never()).prompt(any());
        }
    }

    @Test
    public void validateToolArgumentsWithoutTruncatingIds() {
        Assert.assertEquals(PlayAction.compile(new CallToolRequest(PlayAction.NAME,
                Map.of("requestId", "request", "cardId", 1L, "abilityId", 2))), new PlayAction("request", 1, 2));
        for (Object invalid : List.of(-1, 1.5, "1", Long.MAX_VALUE, true)) {
            Assert.expectThrows(IllegalArgumentException.class, () -> PlayAction.compile(
                    new CallToolRequest(PlayAction.NAME,
                            Map.of("requestId", "request", "cardId", invalid, "abilityId", 2))));
        }
        for (Map<String, Object> invalid : List.<Map<String, Object>>of(Map.of(),
                Map.of("requestId", "request", "cardId", 1),
                Map.of("requestId", " ", "cardId", 1, "abilityId", 2),
                Map.of("requestId", "request", "cardId", 1, "abilityId", 2, "extra", true))) {
            Assert.expectThrows(IllegalArgumentException.class, () -> PlayAction.compile(
                    new CallToolRequest(PlayAction.NAME, invalid)));
        }
    }
}
