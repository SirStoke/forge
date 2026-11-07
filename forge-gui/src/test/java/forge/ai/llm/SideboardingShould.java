package forge.ai.llm;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema.McpServerHttp;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.McpCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionConfigSelect;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionConfigSelectOption;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionConfigOptionResponse;
import forge.ai.LobbyPlayerAi;
import forge.ai.llm.tools.ObserveDeck;
import forge.ai.llm.tools.SubmitSideboardPlan;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.event.GameEventGameFinished;
import forge.game.event.GameEventGameStarted;
import forge.game.player.RegisteredPlayer;
import forge.item.PaperCard;
import forge.util.Lang;
import forge.util.Localizer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SideboardingShould {
    @BeforeClass
    public void initializeLanguage() {
        Localizer.getInstance().initialize("en-US", "res/languages/");
        Lang.createInstance("en-US");
    }

    private PaperCard card(String name, String edition) {
        return new PaperCard(CardRules.fromScript(List.of("Name:" + name, "ManaCost:0", "Types:Artifact", "Oracle:")),
                edition, CardRarity.Common);
    }

    private Deck deck() {
        final var deck = new Deck("Sideboarding");
        deck.getMain().add(card("Main card", "ONE"), 60);
        deck.getOrCreate(DeckSection.Sideboard).add(card("Artifact answer", "ONE"), 2);
        deck.getOrCreate(DeckSection.Sideboard).add(card("Burn answer", "ONE"), 3);
        return deck;
    }

    private Game game(Deck deck) {
        final var rules = new GameRules(GameType.Constructed);
        rules.setAISideboardingEnabled(true);
        return new Match(rules, List.of(
                new RegisteredPlayer(deck).setPlayer(new LobbyPlayerAi("One", null)),
                new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("Two", null))), "Sideboarding").createGame();
    }

    @Test
    public void validateCopiesIndicesSizesAndPreserveExactPrintings() {
        final var deck = deck();
        final var options = new SideboardOptions(deck, GameType.Constructed);
        final var plan = new SubmitSideboardPlan("id", List.of(0, 0), List.of(0, 0));
        final var result = options.apply(plan);
        Assert.assertEquals(result.size(), 60);
        Assert.assertEquals(result.stream().filter(c -> c.getName().equals("Artifact answer")).count(), 2L);
        Assert.assertEquals(deck.getMain().countAll(), 60, "Building a result must not mutate the registered deck");
        Assert.assertEquals(options.report(plan), List.of("sideboarded out 2x Main card.",
                "sideboarded in 2x Artifact answer."));
        for (var invalid : List.of(new SubmitSideboardPlan("id", List.of(), List.of(0, 0, 0)),
                new SubmitSideboardPlan("id", List.of(0), List.of()),
                new SubmitSideboardPlan("id", List.of(1), List.of(0)),
                new SubmitSideboardPlan("id", List.of(), List.of(-1)))) {
            Assert.expectThrows(IllegalArgumentException.class, () -> options.validate(invalid));
        }
        Assert.assertEquals(options.apply(new SubmitSideboardPlan("id", List.of(), List.of(0))).size(), 61,
                "Legal unequal in/out counts are allowed");
        Assert.assertEquals(options.apply(new SubmitSideboardPlan("id", List.of(), List.of())), deck.getMain().toFlatList());
        final var printing = card("Main card", "TWO");
        deck.getMain().add(printing, 1);
        final var printedOptions = new SideboardOptions(deck, GameType.Constructed);
        final var printedResult = printedOptions.apply(new SubmitSideboardPlan("id", List.of(0), List.of(0)));
        Assert.assertEquals(printedResult.stream().filter(printing::equals).count(), 1L);

        final var fullSideboard = deck();
        fullSideboard.getMain().add(card("Main card", "ONE"), 1);
        fullSideboard.get(DeckSection.Sideboard).add(card("Burn answer", "ONE"), 10);
        Assert.expectThrows(IllegalArgumentException.class, () -> new SideboardOptions(fullSideboard, GameType.Constructed)
                .validate(new SubmitSideboardPlan("id", List.of(0), List.of())));
    }

    private AcpSyncClient client(AtomicReference<String> url) {
        final var client = mock(AcpSyncClient.class);
        when(client.initialize()).thenReturn(InitializeResponse.ok(new AgentCapabilities(false,
                new McpCapabilities(true, false), null)));
        final var async = mock(AcpAsyncClient.class);
        when(client.async()).thenReturn(async);
        when(async.prompt(any())).thenAnswer(invocation -> Mono.fromCallable(() -> client.prompt(invocation.getArgument(0))));
        when(client.newSession(any())).thenAnswer(invocation -> {
            final com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest request = invocation.getArgument(0);
            url.set(((McpServerHttp) request.mcpServers().get(0)).url());
            return new NewSessionResponse("sideboard-test");
        });
        final var model = SessionConfigSelect.builder().id("model").name("Model").currentValue(AcpAgent.MODEL)
                .options(List.of(new SessionConfigSelectOption(AcpAgent.MODEL, AcpAgent.MODEL))).build();
        final var reasoning = SessionConfigSelect.builder().id("reasoning_effort").name("Reasoning effort")
                .currentValue(AcpAgent.REASONING_EFFORT).options(List.of(new SessionConfigSelectOption(
                        AcpAgent.REASONING_EFFORT, AcpAgent.REASONING_EFFORT))).build();
        when(client.setSessionConfigOption(any())).thenReturn(new SetSessionConfigOptionResponse(List.of(model, reasoning)));
        return client;
    }

    private McpSyncClient connect(String url) {
        final var transport = HttpClientStreamableHttpTransport
                .builder(URI.create(url).resolve("/").toString()).endpoint("/mcp").build();
        final var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build();
        client.initialize();
        return client;
    }

    @Test
    public void acceptOnlyValidMcpPlansBeforeGameStartThenReuseTheSessionAndRefreshTheDeck() {
        final var game = game(deck());
        final var player = game.getPlayers().get(0);
        final var url = new AtomicReference<String>();
        final var client = client(url);
        final var starts = new AtomicInteger();
        final var controller = new PlayerControllerLlm(game, player, player.getLobbyPlayer(), () -> {
            starts.incrementAndGet();
            return AcpAgent.initialize(client, Path.of("."), player);
        });
        final var submittedId = new AtomicReference<String>();
        when(client.prompt(any())).thenAnswer(invocation -> {
            final PromptRequest request = invocation.getArgument(0);
            if (!request.text().contains("\nProtocol: sideboard")) return null;
            final String id = request.text().lines().filter(line -> line.startsWith("Request ID: "))
                    .findFirst().orElseThrow().substring("Request ID: ".length());
            submittedId.set(id);
            try (var mcp = connect(url.get())) {
                Assert.assertTrue(mcp.callTool(new CallToolRequest(SubmitSideboardPlan.NAME,
                        Map.of("requestId", "stale", "out", List.of(), "in", List.of()))).isError());
                Assert.assertTrue(mcp.callTool(new CallToolRequest(SubmitSideboardPlan.NAME,
                        Map.of("requestId", id, "out", List.of(), "in", List.of(0, 0, 0)))).isError());
                Assert.assertFalse(mcp.callTool(new CallToolRequest(SubmitSideboardPlan.NAME,
                        Map.of("requestId", id, "out", List.of(0, 0), "in", List.of(0, 0)))).isError());
                Assert.assertTrue(mcp.callTool(new CallToolRequest(SubmitSideboardPlan.NAME,
                        Map.of("requestId", id, "out", List.of(), "in", List.of()))).isError());
            }
            throw new IllegalStateException("Transport failed after a valid decision was accepted");
        });
        try {
            Assert.assertTrue(controller.sideboardsByItself());
            final var result = controller.sideboard(player.getRegisteredPlayer().getDeck(), GameType.Constructed, "");
            Assert.assertNotNull(result, "An accepted decision survives a subsequent transport failure");
            Assert.assertEquals(result.stream().filter(c -> c.getName().equals("Artifact answer")).count(), 2L);
            final var registered = player.getRegisteredPlayer().getDeck();
            registered.getMain().clear();
            registered.getMain().add(result);
            registered.get(DeckSection.Sideboard).remove(card("Artifact answer", "ONE"), 2);
            registered.get(DeckSection.Sideboard).add(card("Main card", "ONE"), 2);
            game.fireEvent(new GameEventGameStarted(GameType.Constructed, player, game.getPlayers()));
            Assert.assertEquals(starts.get(), 1, "Sideboarding and gameplay reuse the same ACP adapter");
            try (var mcp = connect(url.get())) {
                final var response = mcp.callTool(new CallToolRequest(ObserveDeck.NAME, Map.of()));
                Assert.assertFalse(response.isError());
                final String text = ((TextContent) response.content().get(0)).text();
                Assert.assertTrue(text.contains("\"count\":58"), text);
                Assert.assertTrue(text.contains("Artifact answer"), text);
            }
        } finally {
            game.fireEvent(new GameEventGameFinished());
        }
        verify(client).close();
        Assert.assertNotNull(submittedId.get());
    }

    @Test
    public void keepTheDeckOnUnansweredRequestsAndSkipDisabledOrSimulatedSideboarding() {
        final var game = game(deck());
        final var player = game.getPlayers().get(0);
        final var starts = new AtomicInteger();
        final var fallbacks = new AtomicInteger();
        final var client = client(new AtomicReference<>());
        final var controller = new PlayerControllerLlm(game, player, player.getLobbyPlayer(), () -> {
            starts.incrementAndGet();
            return AcpAgent.initialize(client, Path.of("."), player);
        }) {
            @Override protected void onLlmFallback(String title) { fallbacks.incrementAndGet(); }
        };
        final var deck = player.getRegisteredPlayer().getDeck();
        try {
            game.getRules().setAISideboardingEnabled(false);
            Assert.assertFalse(controller.sideboardsByItself());
            Assert.assertNull(controller.sideboard(deck, GameType.Constructed, ""));
            Assert.assertEquals(starts.get(), 0);
            game.getRules().setAISideboardingEnabled(true);
            Assert.assertNull(controller.sideboard(deck, GameType.Constructed, ""));
            Assert.assertEquals(starts.get(), 1);
            Assert.assertEquals(fallbacks.get(), 1);
            Assert.assertEquals(deck.getMain().countAll(), 60);
            game.markAsSimulationCopy();
            Assert.assertNull(controller.sideboard(deck, GameType.Constructed, ""));
            Assert.assertEquals(starts.get(), 1);
        } finally {
            game.fireEvent(new GameEventGameFinished());
        }
    }

    @Test
    public void rejectMalformedSubmissions() {
        for (var args : List.<Map<String, Object>>of(Map.of("requestId", "id", "out", List.of()),
                Map.of("requestId", "", "out", List.of(), "in", List.of()),
                Map.of("requestId", "id", "out", List.of(1.5), "in", List.of()),
                Map.of("requestId", "id", "out", List.of(), "in", List.of(-1)))) {
            Assert.expectThrows(IllegalArgumentException.class,
                    () -> SubmitSideboardPlan.compile(new CallToolRequest(SubmitSideboardPlan.NAME, args)));
        }
    }
}
