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
import forge.ai.llm.tools.PlayCardFromHand;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class HandPlayShould {
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
            game = new Match(new GameRules(GameType.Constructed), players, "Hand play").createGame();
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
            return new CallToolRequest(PlayCardFromHand.NAME,
                    Map.of("requestId", requestId, "cardId", cardId, "abilityId", abilityId));
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
            Assert.assertTrue(session.controller.playChosenSpellAbility(selected.get(0)));
            Assert.assertEquals(session.game.getStack().size(), 1);
            final var cast = session.game.getStack().peek().getSpellAbility();
            Assert.assertEquals(cast.getHostCard().getName(), "Lightning Bolt");
            Assert.assertTrue(cast.isTargetNumberValid());
            Assert.assertTrue(cast.getTargets().contains(session.opponent));
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
            Assert.assertNull(session.controller.chooseSpellAbilityToPlay());
        }
    }

    @Test
    public void validateToolArgumentsWithoutTruncatingIds() {
        Assert.assertEquals(PlayCardFromHand.compile(new CallToolRequest(PlayCardFromHand.NAME,
                Map.of("requestId", "request", "cardId", 1L, "abilityId", 2))), new PlayCardFromHand("request", 1, 2));
        for (Object invalid : List.of(-1, 1.5, "1", Long.MAX_VALUE, true)) {
            Assert.expectThrows(IllegalArgumentException.class, () -> PlayCardFromHand.compile(
                    new CallToolRequest(PlayCardFromHand.NAME,
                            Map.of("requestId", "request", "cardId", invalid, "abilityId", 2))));
        }
        for (Map<String, Object> invalid : List.<Map<String, Object>>of(Map.of(),
                Map.of("requestId", "request", "cardId", 1),
                Map.of("requestId", " ", "cardId", 1, "abilityId", 2),
                Map.of("requestId", "request", "cardId", 1, "abilityId", 2, "extra", true))) {
            Assert.expectThrows(IllegalArgumentException.class, () -> PlayCardFromHand.compile(
                    new CallToolRequest(PlayCardFromHand.NAME, invalid)));
        }
    }
}
