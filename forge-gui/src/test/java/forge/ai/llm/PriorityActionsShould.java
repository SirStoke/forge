package forge.ai.llm;

import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.McpCapabilities;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.McpServerHttp;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionConfigSelect;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionConfigSelectOption;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionConfigOptionResponse;
import forge.ai.LobbyPlayerAi;
import forge.ai.llm.tools.CancelAction;
import forge.ai.llm.tools.GetAvailableActions;
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
import forge.game.player.PlayerController.FullControlFlag;
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
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
        final AcpAsyncClient async = mock(AcpAsyncClient.class);
        final AcpAgent agent;
        final PlayerControllerLlm controller;
        final McpSyncClient mcp;

        Session() {
            when(client.async()).thenReturn(async);
            when(async.prompt(any())).thenAnswer(invocation ->
                    Mono.fromCallable(() -> client.prompt(invocation.getArgument(0))));
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
            when(client.initialize()).thenReturn(InitializeResponse.ok(new AgentCapabilities(false,
                    new McpCapabilities(true, false), null)));
            when(client.newSession(any())).thenAnswer(invocation -> {
                final NewSessionRequest request = invocation.getArgument(0);
                url.set(((McpServerHttp) request.mcpServers().get(0)).url());
                return new NewSessionResponse("test-session");
            });
            final var model = SessionConfigSelect.builder().id("model").name("Model")
                    .currentValue(AcpAgent.MODEL)
                    .options(List.of(new SessionConfigSelectOption(AcpAgent.MODEL, AcpAgent.MODEL))).build();
            final var reasoning = SessionConfigSelect.builder().id("reasoning_effort").name("Reasoning effort")
                    .currentValue(AcpAgent.REASONING_EFFORT)
                    .options(List.of(new SessionConfigSelectOption(AcpAgent.REASONING_EFFORT,
                            AcpAgent.REASONING_EFFORT))).build();
            when(client.setSessionConfigOption(any())).thenReturn(new SetSessionConfigOptionResponse(List.of(model, reasoning)));
            agent = AcpAgent.initialize(client, Path.of("."), player);
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

        CallToolRequest list(String requestId) {
            return new CallToolRequest(GetAvailableActions.NAME, Map.of("requestId", requestId));
        }

        PriorityActionsSnapshot actions(PromptRequest prompt) throws Exception {
            final var result = mcp.callTool(list(requestId(prompt)));
            Assert.assertFalse(result.isError(), result.toString());
            final var snapshot = McpJsonDefaults.getMapper().readValue(
                    ((TextContent) result.content().get(0)).text(), PriorityActionsSnapshot.class);
            Assert.assertEquals(snapshot.requestId(), requestId(prompt));
            Assert.assertFalse(snapshot.affordabilityChecked());
            return snapshot;
        }

        SpellAbility choose(Card card) {
            return choose(card, null);
        }

        SpellAbility choose(Card card, String kind) {
            doAnswer(invocation -> {
                final PromptRequest prompt = invocation.getArgument(0);
                final var action = actions(prompt).actions().stream()
                        .filter(option -> option.cardId() == card.getId() && (kind == null || option.kind().equals(kind)))
                        .findFirst().orElseThrow();
                Assert.assertFalse(mcp.callTool(play(requestId(prompt), card.getId(), action.abilityId())).isError());
                return null;
            }).when(client).prompt(any());
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

    private static String requestId(PromptRequest prompt) {
        return prompt.text().lines().filter(line -> line.startsWith("Request ID: "))
                .findFirst().orElseThrow().substring("Request ID: ".length());
    }

    private static String describeActions(Player player, Map<PriorityDecision.PlayAction, SpellAbility> options) {
        return describeActions(PriorityActionsSnapshot.capture(player, "request", options));
    }

    private static String describeActions(PriorityActionsSnapshot snapshot) {
        return snapshot.actions().stream().map(action -> action.name() + " [" + action.zone() + "; "
                + action.kind() + "; cost: " + action.cost() + "]: cardId=" + action.cardId()
                + ", abilityId=" + action.abilityId() + ". " + action.description()).collect(java.util.stream.Collectors.joining("\n"));
    }

    @Test
    public void executeASubmittedActionImmediatelyAndBufferTheNextPromptUntilTheTurnEnds() throws Exception {
        final var gameThread = Executors.newSingleThreadExecutor();
        final var firstTurn = new CompletableFuture<PromptResponse>();
        final var secondTurn = new CompletableFuture<PromptResponse>();
        try (var session = new Session()) {
            final Card land = session.card(session.player, ZoneType.Hand, "Forest",
                    "ManaCost:no cost", "Types:Basic Land Forest", "Oracle:");
            final var firstPrompt = new CompletableFuture<PromptRequest>();
            final var secondPrompt = new CompletableFuture<PromptRequest>();
            when(session.async.prompt(any())).thenAnswer(invocation -> {
                final PromptRequest prompt = invocation.getArgument(0);
                if (!firstPrompt.isDone()) {
                    firstPrompt.complete(prompt);
                    return Mono.fromFuture(firstTurn);
                }
                secondPrompt.complete(prompt);
                return Mono.fromFuture(secondTurn);
            });
            final var played = gameThread.submit(() -> {
                final var selected = session.controller.chooseSpellAbilityToPlay();
                return session.controller.playChosenSpellAbility(selected.get(0));
            });
            final var prompt = firstPrompt.get(5, TimeUnit.SECONDS);
            final var action = session.actions(prompt).actions().stream()
                    .filter(option -> option.cardId() == land.getId()).findFirst().orElseThrow();
            Assert.assertFalse(played.isDone());
            Assert.assertFalse(session.mcp.callTool(session.play(requestId(prompt), land.getId(), action.abilityId())).isError());
            Assert.assertTrue(played.get(5, TimeUnit.SECONDS));
            Assert.assertTrue(land.isInZone(ZoneType.Battlefield));
            Assert.assertFalse(firstTurn.isDone(), "Game execution must not wait for the agent turn");

            final var kept = gameThread.submit(() -> session.controller.mulliganKeepHand(session.player, 0));
            // The first decision releases the game, but OpenCode permits only one active transport prompt.
            Assert.expectThrows(java.util.concurrent.TimeoutException.class, () -> secondPrompt.get(100, TimeUnit.MILLISECONDS));
            Assert.assertFalse(firstTurn.isDone(), "Buffering must not cancel the previous response");
            firstTurn.complete(null);
            final var next = secondPrompt.get(5, TimeUnit.SECONDS);
            Assert.assertFalse(session.mcp.callTool(new CallToolRequest("submit_mulligan_decision",
                    Map.of("requestId", requestId(next), "keep", false))).isError());
            Assert.assertFalse(kept.get(5, TimeUnit.SECONDS));
            Assert.assertFalse(secondTurn.isDone(), "Mulligan submission must also release the game thread");
        } finally {
            gameThread.shutdownNow();
        }
        Assert.assertFalse(firstTurn.isCancelled(), "Completed responses must not be cancelled");
        Assert.assertTrue(secondTurn.isCancelled());
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
            doAnswer(invocation -> {
                final PromptRequest prompt = invocation.getArgument(0);
                final String id = requestId(prompt);
                Assert.assertTrue(prompt.text().contains("Protocol: priority (session instructions)."));
                Assert.assertFalse(prompt.text().contains("Forest"), "Priority prompts must not contain the action list");
                Assert.assertFalse(prompt.text().contains("cardId="));
                Assert.assertTrue(session.mcp.callTool(session.list("wrong-request")).isError());
                Assert.assertTrue(session.mcp.callTool(new CallToolRequest(CancelAction.NAME,
                        Map.of("requestId", id))).isError(), "Priority itself cannot be canceled");
                final var snapshot = session.actions(prompt);
                Assert.assertEquals(session.actions(prompt), snapshot, "Reads must not consume the decision");
                final String actions = describeActions(snapshot);
                Assert.assertTrue(actions.contains("cardId=" + second.getId() + ", abilityId=" + abilityId));
                Assert.assertFalse(actions.contains("Opponent secret"));
                Assert.assertTrue(session.mcp.callTool(session.play(id, opponentCard.getId(), abilityId)).isError());
                Assert.assertTrue(session.mcp.callTool(session.play(id, second.getId(), Integer.MAX_VALUE)).isError());
                Assert.assertFalse(session.mcp.callTool(session.play(id, second.getId(), abilityId)).isError());
                Assert.assertTrue(session.mcp.callTool(new CallToolRequest(PassPriority.NAME,
                        Map.of("requestId", id))).isError());
                Assert.assertTrue(session.mcp.callTool(session.list(id)).isError(), "Accepted requests must expire");
                Assert.assertTrue(second.isInZone(ZoneType.Hand), "The MCP thread must not execute the play");
                return null;
            }).when(session.client).prompt(any());
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
            doAnswer(invocation -> {
                final String id = requestId(invocation.getArgument(0));
                Assert.assertFalse(session.mcp.callTool(session.play(id, bolt.getId(), abilityId)).isError());
                return null;
            }).when(session.client).prompt(any());
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
            Assert.assertFalse(PriorityActions.available(session.player).isEmpty(), "Tap-cost affordability is not checked when listing");
            Assert.assertFalse(session.controller.playChosenSpellAbility(ability), "Actual payment still rejects a tapped source");
            Assert.assertEquals(session.player.getManaPool().totalMana(), 1);
        }
    }

    @Test
    public void offerHandActivationsAlongsideCastingOptions() {
        try (var session = new Session()) {
            final Card card = session.card(session.player, ZoneType.Hand, "Hand activation",
                    "ManaCost:5", "Types:Artifact", "Oracle:",
                    "A:AB$ GainLife | Cost$ 0 | ActivationZone$ Hand | LifeAmount$ 1 | SpellDescription$ Gain 1 life.");
            final SpellAbility ability = session.choose(card, "Activate ability");
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
            Assert.assertTrue(describeActions(session.player, PriorityActions.available(session.player))
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
            final String prompt = describeActions(session.player, options);
            Assert.assertTrue(prompt.contains("[" + zone + "; Cast spell; cost:"));
            Assert.assertFalse(prompt.contains("Forbidden external spell"));
            Assert.assertTrue(session.controller.playChosenSpellAbility(ability));
            Assert.assertTrue(card.isInZone(ZoneType.Stack));
            Assert.assertEquals(session.game.getStack().size(), 1);
        }
    }

    @Test
    public void offerFlashbackAndUnaffordableActionsWhileExcludingHiddenCardsAndMissingTargets() {
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
            Assert.assertEquals(options.size(), 2);
            Assert.assertTrue(options.values().stream().anyMatch(SpellAbility::isFlashback));
            final String prompt = describeActions(session.player, options);
            Assert.assertTrue(prompt.contains("Flashback spell [Graveyard; Cast spell; cost:"));
            Assert.assertTrue(prompt.contains("Unaffordable activation"));
            for (String absent : List.of("Secret hand", "Secret library", "Opponent ability", "No legal target")) {
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
            Assert.assertFalse(describeActions(session.player, options).contains("Hidden second card"));
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
            doAnswer(invocation -> {
                final String id = requestId(invocation.getArgument(0));
                final String old = previousId.getAndSet(id);
                if (old == null) {
                    Assert.assertFalse(session.mcp.callTool(new CallToolRequest(PassPriority.NAME,
                            Map.of("requestId", id))).isError());
                    Assert.assertTrue(session.mcp.callTool(session.play(id, card.getId(), abilityId)).isError());
                    Assert.assertTrue(session.mcp.callTool(session.list(id)).isError());
                    return null;
                }
                Assert.assertTrue(session.mcp.callTool(session.play(old, card.getId(), abilityId)).isError());
                Assert.assertTrue(session.mcp.callTool(session.list(old)).isError());
                Assert.assertFalse(session.mcp.callTool(session.play(id, card.getId(), abilityId)).isError());
                throw new IllegalStateException("ACP failed after accepting the play");
            }).when(session.client).prompt(any());
            Assert.assertNull(session.controller.chooseSpellAbilityToPlay());
            final var selected = session.controller.chooseSpellAbilityToPlay();
            Assert.assertNotNull(selected);
            Assert.assertSame(selected.get(0).getHostCard(), card);
            Assert.assertTrue(session.controller.playChosenSpellAbility(selected.get(0)));
            Assert.assertEquals(session.game.getStack().size(), 1);
        }
    }

    @Test
    public void distinguishCastingOptionsAndIncludeUnaffordableSpellsWhileEnforcingTiming() {
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
            doAnswer(invocation -> {
                final PromptRequest prompt = invocation.getArgument(0);
                final String actions = describeActions(session.actions(prompt));
                Assert.assertTrue(actions.contains("abilityId=" + firstId));
                Assert.assertTrue(actions.contains("abilityId=" + secondId));
                Assert.assertTrue(actions.contains("Unaffordable spell"));
                Assert.assertFalse(actions.contains("Sorcery speed"));
                Assert.assertFalse(actions.contains("Forest"));
                Assert.assertFalse(session.mcp.callTool(session.play(requestId(prompt), instant.getId(), secondId)).isError());
                return null;
            }).when(session.client).prompt(any());
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
            doAnswer(invocation -> {
                lastId.set(requestId(invocation.getArgument(0)));
                return null;
            }).when(session.client).prompt(any());
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
            Assert.assertTrue(session.mcp.callTool(session.list(lastId.get())).isError());
            session.game.markAsSimulationCopy();
            org.mockito.Mockito.clearInvocations(session.client);
            Assert.assertNotNull(session.controller.chooseSpellAbilityToPlay(), "Simulation copies use local AI");
            org.mockito.Mockito.verify(session.client, org.mockito.Mockito.never()).prompt(any());
        }
    }

    @Test
    public void listMissingNonmanaResourcesWithoutProbingPaymentOrChangingControllerSettings() {
        try (var session = new Session()) {
            final Card source = session.card(session.player, ZoneType.Battlefield, "Missing sacrifice",
                    "ManaCost:0", "Types:Artifact", "Oracle:",
                    "A:AB$ GainLife | Cost$ Sac<1/Creature> | LifeAmount$ 1 | SpellDescription$ Gain 1 life.");
            session.card(session.player, ZoneType.Hand, "Missing mana", "ManaCost:9", "Types:Artifact", "Oracle:");
            final var controls = session.controller.getFullControl();
            Assert.assertFalse(controls.contains(FullControlFlag.AllowPaymentStartWithMissingResources));
            org.mockito.Mockito.clearInvocations(session.client);
            final var options = PriorityActions.available(session.player);
            Assert.assertEquals(options.size(), 2);
            Assert.assertFalse(controls.contains(FullControlFlag.AllowPaymentStartWithMissingResources));
            org.mockito.Mockito.verify(session.client, org.mockito.Mockito.never()).prompt(any());
            final var activation = options.values().stream().filter(sa -> sa.getHostCard() == source).findFirst().orElseThrow();
            Assert.assertFalse(activation.canPlay(), "Actual cost eligibility must still be enforced outside listing");
            controls.add(FullControlFlag.AllowPaymentStartWithMissingResources);
            Assert.assertEquals(PriorityActions.available(session.player).size(), 2);
            Assert.assertTrue(controls.contains(FullControlFlag.AllowPaymentStartWithMissingResources), "Preserve existing full control");
            controls.remove(FullControlFlag.AllowPaymentStartWithMissingResources);
        }
    }

    @Test
    public void cancelPartialManaPaymentAndChooseAnotherActionWithoutPassingPriority() {
        try (var session = new Session()) {
            final Card first = session.card(session.player, ZoneType.Battlefield, "First source",
                    "ManaCost:0", "Types:Artifact", "Oracle:",
                    "A:AB$ Mana | Cost$ T | Produced$ R | SpellDescription$ Add {R}.");
            session.card(session.player, ZoneType.Battlefield, "Second source",
                    "ManaCost:0", "Types:Artifact", "Oracle:",
                    "A:AB$ Mana | Cost$ T | Produced$ R | SpellDescription$ Add {R}.");
            final Card spell = session.card(session.player, ZoneType.Hand, "Two mana spell",
                    "ManaCost:1 R", "Types:Instant", "Oracle:",
                    "A:SP$ GainLife | Defined$ You | LifeAmount$ 1 | SpellDescription$ Gain 1 life.");
            final Card other = session.card(session.player, ZoneType.Hand, "Another action",
                    "ManaCost:0", "Types:Artifact", "Oracle:");
            session.game.getAction().checkStaticAbilities();
            final SpellAbility chosen = session.choose(spell);
            final var canceledId = new AtomicReference<String>();
            final java.util.concurrent.atomic.AtomicInteger step = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(invocation -> {
                final PromptRequest prompt = invocation.getArgument(0);
                final String id = requestId(prompt);
                Assert.assertTrue(prompt.text().contains("Cancel action: allowed"));
                Assert.assertTrue(session.mcp.callTool(new CallToolRequest(CancelAction.NAME,
                        Map.of("requestId", "stale"))).isError());
                if (step.getAndIncrement() < 2) {
                    final String label = step.get() == 1 ? "Activate mana ability: First source" : "Spend floating";
                    final int index = prompt.text().lines().filter(line -> line.matches("\\d+: .*"))
                            .filter(line -> line.contains(label))
                            .mapToInt(line -> Integer.parseInt(line.substring(0, line.indexOf(':')))).findFirst().orElseThrow();
                    Assert.assertFalse(session.mcp.callTool(new CallToolRequest(SubmitChoice.NAME,
                            Map.of("requestId", id, "choices", List.of(index)))).isError());
                    Assert.assertTrue(session.mcp.callTool(new CallToolRequest(CancelAction.NAME,
                            Map.of("requestId", id))).isError(), "An accepted choice cannot be canceled afterward");
                } else {
                    canceledId.set(id);
                    Assert.assertTrue(first.isTapped(), "Exercise cancellation after partially spending mana");
                    Assert.assertFalse(session.mcp.callTool(new CallToolRequest(CancelAction.NAME,
                            Map.of("requestId", id))).isError());
                    Assert.assertTrue(session.mcp.callTool(new CallToolRequest(SubmitChoice.NAME,
                            Map.of("requestId", id, "choices", List.of()))).isError());
                    Assert.assertTrue(session.mcp.callTool(new CallToolRequest(CancelAction.NAME,
                            Map.of("requestId", id))).isError(), "Cancel can only win once");
                    Assert.assertTrue(spell.isInZone(ZoneType.Stack), "Rollback must wait for the game thread");
                    throw new IllegalStateException("ACP failed after accepting cancellation");
                }
                return null;
            }).when(session.client).prompt(any());
            Assert.assertFalse(session.controller.playChosenSpellAbility(chosen));
            Assert.assertEquals(step.get(), 3);
            Assert.assertNotNull(canceledId.get());
            Assert.assertTrue(session.game.getStack().isEmpty());
            Assert.assertTrue(session.game.getStackZone().isEmpty());
            Assert.assertTrue(spell.isInZone(ZoneType.Hand));
            Assert.assertFalse(first.isTapped(), "Undoable mana production must be refunded");
            Assert.assertEquals(session.player.getManaPool().totalMana(), 0);
            Assert.assertTrue(session.controller.playChosenSpellAbility(session.choose(other)),
                    "Cancellation must offer another priority decision immediately");
            Assert.assertTrue(other.isInZone(ZoneType.Stack));
        }
    }

    @Test
    public void propagateCancellationFromManaSourceCostsToTheOuterAction() {
        try (var session = new Session()) {
            final Card source = session.card(session.player, ZoneType.Battlefield, "Life source",
                    "ManaCost:0", "Types:Artifact", "Oracle:",
                    "A:AB$ Mana | Cost$ T PayLife<2> | Produced$ R | SpellDescription$ Add {R}.");
            final Card spell = session.card(session.player, ZoneType.Hand, "One mana spell",
                    "ManaCost:R", "Types:Instant", "Oracle:",
                    "A:SP$ GainLife | Defined$ You | LifeAmount$ 1 | SpellDescription$ Gain 1 life.");
            final Card other = session.card(session.player, ZoneType.Hand, "Another action",
                    "ManaCost:0", "Types:Artifact", "Oracle:");
            final var count = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(invocation -> {
                final PromptRequest prompt = invocation.getArgument(0);
                count.incrementAndGet();
                final String id = requestId(prompt);
                if (prompt.text().contains("Decision: Order cost")) {
                    final List<Integer> order = prompt.text().lines().filter(line -> line.matches("\\d+: .*"))
                            .map(line -> Integer.parseInt(line.substring(0, line.indexOf(':')))).toList();
                    Assert.assertFalse(session.mcp.callTool(new CallToolRequest(SubmitChoice.NAME,
                            Map.of("requestId", id, "choices", order))).isError());
                } else if (prompt.text().contains("Pay this cost?")) {
                    Assert.assertFalse(session.mcp.callTool(new CallToolRequest(CancelAction.NAME,
                            Map.of("requestId", id))).isError());
                } else {
                    final int index = prompt.text().lines().filter(line -> line.matches("\\d+: .*"))
                            .filter(line -> line.contains("Activate mana ability: Life source"))
                            .mapToInt(line -> Integer.parseInt(line.substring(0, line.indexOf(':')))).findFirst().orElseThrow();
                    Assert.assertFalse(session.mcp.callTool(new CallToolRequest(SubmitChoice.NAME,
                            Map.of("requestId", id, "choices", List.of(index)))).isError());
                }
                return null;
            }).when(session.client).prompt(any());
            Assert.assertFalse(session.controller.playChosenSpellAbility(spell.getSpellAbilities().get(0)));
            Assert.assertEquals(count.get(), 3, "Nested cancellation must stop the outer mana loop");
            Assert.assertTrue(spell.isInZone(ZoneType.Hand));
            Assert.assertTrue(session.game.getStack().isEmpty());
            Assert.assertFalse(source.isTapped());
            Assert.assertEquals(session.player.getLife(), 20);
            Assert.assertEquals(session.player.getManaPool().totalMana(), 0);
            Assert.assertNotNull(session.choose(other));
        }
    }

    @DataProvider
    public Object[][] cancellableCastingSteps() {
        return new Object[][] {
                { "targets", new String[] { "ManaCost:0", "Types:Instant", "Oracle:",
                        "A:SP$ DealDamage | ValidTgts$ Any | NumDmg$ 1 | SpellDescription$ Deal 1 damage." } },
                { "modes", new String[] { "ManaCost:0", "Types:Instant", "Oracle:",
                        "A:SP$ Charm | Choices$ Draw,Life | CharmNum$ 1 | SpellDescription$ Choose one.",
                        "SVar:Draw:DB$ Draw | Defined$ You | NumCards$ 1 | SpellDescription$ Draw a card.",
                        "SVar:Life:DB$ GainLife | Defined$ You | LifeAmount$ 1 | SpellDescription$ Gain 1 life." } },
                { "mana with no sources", new String[] { "ManaCost:R", "Types:Instant", "Oracle:",
                        "A:SP$ GainLife | Defined$ You | LifeAmount$ 1 | SpellDescription$ Gain 1 life." } },
                { "X", new String[] { "ManaCost:X", "Types:Instant", "Oracle:",
                        "A:SP$ GainLife | Defined$ You | LifeAmount$ 1 | SpellDescription$ Gain 1 life." } },
                { "casting variant", new String[] { "ManaCost:0", "Types:Instant", "Oracle:",
                        "K:AlternateAdditionalCost:0:1",
                        "A:SP$ GainLife | Defined$ You | LifeAmount$ 1 | SpellDescription$ Gain 1 life." } },
                { "optional costs", new String[] { "ManaCost:0", "Types:Instant", "Oracle:", "K:Kicker:1",
                        "A:SP$ GainLife | Defined$ You | LifeAmount$ 1 | SpellDescription$ Gain 1 life." } },
                { "sacrifice", new String[] { "ManaCost:0", "Types:Instant", "Oracle:",
                        "A:SP$ GainLife | Cost$ Sac<1/Creature> | Defined$ You | LifeAmount$ 1 | SpellDescription$ Gain 1 life." } },
                { "life payment", new String[] { "ManaCost:0", "Types:Instant", "Oracle:",
                        "A:SP$ GainLife | Cost$ PayLife<2> | Defined$ You | LifeAmount$ 1 | SpellDescription$ Gain 1 life." } }
        };
    }

    @Test(dataProvider = "cancellableCastingSteps")
    public void cancelDuringCastingWithoutCompletingTheChoiceUsingAi(String step, String[] script) throws Exception {
        try (var session = new Session()) {
            final Card spell = session.card(session.player, ZoneType.Hand, "Cancel " + step, script);
            session.card(session.player, ZoneType.Battlefield, "Creature one",
                    "ManaCost:0", "Types:Creature", "PT:1/1", "Oracle:");
            session.card(session.player, ZoneType.Battlefield, "Creature two",
                    "ManaCost:0", "Types:Creature", "PT:1/1", "Oracle:");
            final Card other = session.card(session.player, ZoneType.Hand, "Another action",
                    "ManaCost:0", "Types:Artifact", "Oracle:");
            final var count = new java.util.concurrent.atomic.AtomicInteger();
            final var cancellationReply = new CompletableFuture<Void>();
            doAnswer(invocation -> {
                final PromptRequest prompt = invocation.getArgument(0);
                Assert.assertTrue(prompt.text().contains("Cancel action: allowed"), prompt.text());
                Assert.assertFalse(session.mcp.callTool(new CallToolRequest(CancelAction.NAME,
                        Map.of("requestId", requestId(prompt)))).isError());
                count.incrementAndGet();
                cancellationReply.complete(null);
                return null;
            }).when(session.client).prompt(any());
            Assert.assertFalse(session.controller.playChosenSpellAbility(spell.getSpellAbilities().get(0)), step);
            // The game can resume before the mock agent even receives its MCP reply.
            // Wait here only so fixture teardown doesn't close that exchange mid-response.
            cancellationReply.get(5, TimeUnit.SECONDS);
            Assert.assertEquals(count.get(), 1, "Cancellation must stop casting without another prompt");
            Assert.assertTrue(spell.isInZone(ZoneType.Hand));
            Assert.assertTrue(session.game.getStack().isEmpty());
            Assert.assertTrue(session.game.getStackZone().isEmpty());
            Assert.assertEquals(session.player.getCardsIn(ZoneType.Battlefield).size(), 2);
            Assert.assertEquals(session.player.getLife(), 20);
            Assert.assertNotNull(session.choose(other), "Canceled " + step + " must retain priority");
        }
    }

    @Test
    public void rejectCancellationForOrdinaryChoicesWithoutConsumingTheRequest() {
        try (var session = new Session()) {
            doAnswer(invocation -> {
                final PromptRequest prompt = invocation.getArgument(0);
                final String id = requestId(prompt);
                Assert.assertTrue(prompt.text().contains("Cancel action: forbidden"));
                Assert.assertTrue(session.mcp.callTool(new CallToolRequest(CancelAction.NAME,
                        Map.of("requestId", id))).isError());
                Assert.assertFalse(session.mcp.callTool(new CallToolRequest(SubmitChoice.NAME,
                        Map.of("requestId", id, "value", 2))).isError());
                return null;
            }).when(session.client).prompt(any());
            Assert.assertEquals(session.controller.chooseNumber(null, "Effect choice", 1, 3), 2);
        }
    }

    @Test
    public void validateCancelActionToolArguments() {
        Assert.assertEquals(CancelAction.compile(new CallToolRequest(CancelAction.NAME,
                Map.of("requestId", "request"))), new CancelAction("request"));
        for (Map<String, Object> invalid : List.<Map<String, Object>>of(Map.of(), Map.of("requestId", " "),
                Map.of("requestId", 1), Map.of("requestId", "request", "extra", true))) {
            Assert.expectThrows(IllegalArgumentException.class, () -> CancelAction.compile(
                    new CallToolRequest(CancelAction.NAME, invalid)));
        }
    }

    @Test
    public void validateAvailableActionsToolArguments() {
        Assert.assertEquals(GetAvailableActions.compile(new CallToolRequest(GetAvailableActions.NAME,
                Map.of("requestId", "request"))), new GetAvailableActions("request"));
        for (Map<String, Object> invalid : List.<Map<String, Object>>of(Map.of(), Map.of("requestId", " "),
                Map.of("requestId", 1), Map.of("requestId", "request", "extra", true))) {
            Assert.expectThrows(IllegalArgumentException.class, () -> GetAvailableActions.compile(
                    new CallToolRequest(GetAvailableActions.NAME, invalid)));
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
