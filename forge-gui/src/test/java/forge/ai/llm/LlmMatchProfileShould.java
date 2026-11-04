package forge.ai.llm;

import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionConfigSelect;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionConfigSelectOption;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionConfigOptionResponse;
import forge.ai.LobbyPlayerAi;
import forge.ai.llm.tools.Mulligan;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.event.GameEventGameFinished;
import forge.game.player.RegisteredPlayer;
import forge.util.Localizer;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class LlmMatchProfileShould {
    private String previousFlag;

    @BeforeClass
    public void initializeLanguage() {
        Localizer.getInstance().initialize("en-US", "res/languages/");
    }

    @BeforeMethod
    public void saveFlag() {
        previousFlag = System.getProperty("forge.llm.profile");
        System.setProperty("forge.llm.profile", "true");
    }

    @AfterMethod
    public void restoreFlag() {
        if (previousFlag == null) System.clearProperty("forge.llm.profile");
        else System.setProperty("forge.llm.profile", previousFlag);
    }

    private Game game() {
        return new Match(new GameRules(GameType.Constructed), List.of(
                new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("One", null)),
                new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("Two", null))), "Profile test").createGame();
    }

    @Test
    public void captureTheOptInOnceAndNeverRecordSimulationCopies() {
        final Game game = game();
        System.clearProperty("forge.llm.profile");
        Assert.assertNull(LlmMatchProfile.forGame(game).path());
        System.setProperty("forge.llm.profile", "true");
        Assert.assertNull(LlmMatchProfile.forGame(game).path(), "A match cannot be enabled halfway through");
        final Game simulation = game();
        simulation.markAsSimulationCopy();
        Assert.assertNull(LlmMatchProfile.forGame(simulation).path());
    }

    @Test
    public void recordStartupAndFailedPromptsThroughTheActualAgentBoundary() throws Exception {
        final Game game = game();
        final var profile = LlmMatchProfile.forGame(game);
        final AcpSyncClient client = mock(AcpSyncClient.class);
        when(client.newSession(any())).thenReturn(new NewSessionResponse("agent-seat"));
        final var model = SessionConfigSelect.builder().id("model").name("Model").currentValue(CodexAcpAgent.MODEL)
                .options(List.of(new SessionConfigSelectOption(CodexAcpAgent.MODEL, CodexAcpAgent.MODEL))).build();
        final var reasoning = SessionConfigSelect.builder().id("reasoning_effort").name("Reasoning effort")
                .currentValue(CodexAcpAgent.REASONING_EFFORT)
                .options(List.of(new SessionConfigSelectOption(CodexAcpAgent.REASONING_EFFORT,
                        CodexAcpAgent.REASONING_EFFORT))).build();
        when(client.setSessionConfigOption(any())).thenReturn(new SetSessionConfigOptionResponse(List.of(model, reasoning)));
        try (var agent = CodexAcpAgent.initialize(client, Path.of("."), game.getPlayers().get(0))) {
            doThrow(new IllegalStateException("Prompt failed")).when(client).prompt(any());
            Assert.expectThrows(IllegalStateException.class,
                    () -> agent.prompt("Private prompt", "failed-request", "choice", "Choose a card"));
        } finally {
            game.fireEvent(new GameEventGameFinished());
        }
        final var records = records(profile);
        final var startup = records.stream().filter(r -> "startup".equals(r.get("kind"))).findFirst().orElseThrow();
        final var startupEnd = records.stream().filter(r -> startup.get("id").equals(r.get("id"))
                && "span_end".equals(r.get("event"))).findFirst().orElseThrow();
        Assert.assertEquals(startupEnd.get("sessionId"), "agent-seat");
        Assert.assertTrue(records.stream().anyMatch(r -> "agent_wait".equals(r.get("kind"))));
        final var failed = records.stream().filter(r -> "failed-request".equals(r.get("requestId")))
                .findFirst().orElseThrow();
        Assert.assertEquals(failed.get("outcome"), "error");
        Assert.assertFalse(failed.containsKey("afterDecisionMs"));
        Assert.assertFalse(Files.readString(profile.path()).contains("Private prompt"));
    }

    @Test
    public void correlateCrossThreadToolsAndCountOnlyAcceptedDecisions() throws Exception {
        final Game game = game();
        final var profile = LlmMatchProfile.forGame(game);
        final var session = LlmMatchProfile.session(game.getPlayers().get(0));
        session.configure(CodexAcpAgent.Options.defaults());
        session.sessionId("first-seat");
        final var pending = new CallbackOnce<Boolean, String>();
        final String requestId = pending.register(keep -> "Keep");
        final var repository = new ToolRepository(session);
        repository.register(Mulligan::schema, Mulligan::compile,
                request -> pending.call(request.requestId(), request.keep()));
        final var handler = repository.specifications().get(0).callHandler();
        final String prompt = "Private prompt é";
        try {
            session.beginAction("Cast spell");
            try (var request = session.beginRequest(requestId, "mulligan", "Mulligan", prompt)) {
                CompletableFuture.runAsync(() -> {
                    // Rejected/stale tool invocations must not mark the decision as accepted.
                    Assert.assertTrue(handler.apply(null, new CallToolRequest(Mulligan.NAME,
                            Map.of("requestId", "stale", "keep", true))).isError());
                    session.firstActivity();
                    Assert.assertFalse(handler.apply(null, new CallToolRequest(Mulligan.NAME,
                            Map.of("requestId", requestId, "keep", true))).isError());
                    Assert.assertTrue(handler.apply(null, new CallToolRequest(Mulligan.NAME,
                            Map.of("requestId", requestId, "keep", true))).isError());
                }).join();
            }
            session.endAction("played");
            final var second = LlmMatchProfile.session(game.getPlayers().get(1));
            second.configure(new CodexAcpAgent.Options("other-model", "high"));
            second.sessionId("second-seat");
            try (var request = second.beginRequest("second-request", "choice", "Choose", "")) {
                request.failed();
            }
        } finally {
            game.fireEvent(new GameEventGameFinished());
        }
        final var records = records(profile);
        Assert.assertEquals(records.stream().filter(r -> "match_start".equals(r.get("event"))).count(), 1L);
        Assert.assertEquals(records.stream().filter(r -> "match_end".equals(r.get("event"))).count(), 1L);
        Assert.assertEquals(records.stream().filter(r -> "decision_accepted".equals(r.get("event"))).count(), 1L);
        final var request = records.stream().filter(r -> requestId.equals(r.get("requestId"))
                && r.containsKey("afterDecisionMs")).findFirst().orElseThrow();
        Assert.assertEquals(((Number) request.get("toolCount")).intValue(), 3);
        Assert.assertEquals(((Number) request.get("promptBytes")).intValue(), prompt.getBytes(StandardCharsets.UTF_8).length);
        Assert.assertEquals(request.get("outcome"), Mulligan.NAME);
        Assert.assertTrue(((Number) request.get("decisionMs")).doubleValue() >= 0);
        Assert.assertTrue(((Number) request.get("afterDecisionMs")).doubleValue() >= 0);
        Assert.assertTrue(request.containsKey("firstActivityMs"));
        final var tool = records.stream().filter(r -> "tool".equals(r.get("kind"))).findFirst().orElseThrow();
        Assert.assertEquals(tool.get("parentId"), request.get("id"));
        Assert.assertEquals(tool.get("sessionId"), "first-seat");
        Assert.assertTrue(records.stream().anyMatch(r -> "second-seat".equals(r.get("sessionId"))));
        Assert.assertTrue(records.stream().anyMatch(r -> "second-request".equals(r.get("requestId"))
                && "error".equals(r.get("outcome"))));
        Assert.assertFalse(Files.readString(profile.path()).contains(prompt), "Prompt contents do not belong in timing files");
        final long size = Files.size(profile.path());
        session.fallback("Too late");
        Assert.assertEquals(Files.size(profile.path()), size, "Finished matches cannot reopen the file");
        Assert.assertSame(LlmMatchProfile.forGame(game), profile);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> records(LlmMatchProfile profile) throws Exception {
        final var result = new java.util.ArrayList<Map<String, Object>>();
        for (String line : Files.readAllLines(profile.path())) {
            result.add(McpJsonDefaults.getMapper().readValue(line, Map.class));
        }
        return result;
    }
}
