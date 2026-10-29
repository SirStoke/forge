package forge.gamemodes.match;

import forge.ai.AIOption;
import forge.ai.AiEngine;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.ai.llm.PlayerControllerLlm;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.net.event.UpdateLobbyPlayerEvent;
import forge.util.Localizer;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class LlmEngineShould {
    @BeforeClass
    public void initializeLanguage() {
        Localizer.getInstance().initialize("en-US", "res/languages/");
    }

    @Test
    public void keepTheEngineAcrossUnrelatedLobbyUpdatesAndSerialization() throws Exception {
        final LobbySlot slot = new LobbySlot(LobbySlotType.AI, "Opponent", 0, 0, 1,
                false, true, Collections.emptySet());
        Assert.assertEquals(slot.getAiEngine(), AiEngine.FORGE);

        final UpdateLobbyPlayerEvent selection = UpdateLobbyPlayerEvent.create(
                LobbySlotType.AI, "Opponent", 0, 0, 1, false, false,
                Collections.emptySet(), "Default", AiEngine.LLM);
        Assert.assertTrue(slot.apply(selection));
        slot.apply(UpdateLobbyPlayerEvent.nameUpdate("Renamed opponent"));
        Assert.assertEquals(slot.getAiEngine(), AiEngine.LLM);

        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(slot);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            final LobbySlot restored = (LobbySlot) input.readObject();
            Assert.assertEquals(restored.getAiEngine(), AiEngine.LLM);
            Assert.assertEquals(restored.getName(), "Renamed opponent");
        }

        final UpdateLobbyPlayerEvent legacyUpdate = UpdateLobbyPlayerEvent.create(
                LobbySlotType.AI, "Opponent", 0, 0, 1, false, false,
                Collections.emptySet(), "Default");
        Assert.assertNull(legacyUpdate.getAiEngine());
        slot.apply(legacyUpdate);
        Assert.assertEquals(slot.getAiEngine(), AiEngine.LLM);
    }

    @Test
    public void installDifferentControllersInTheSameGame() {
        final LobbyPlayerAi forge = new LobbyPlayerAi("Forge", Set.of(AIOption.USE_FULL_SIMULATION));
        final LobbyPlayerAi llm = new LobbyPlayerAi("LLM", null, AiEngine.LLM);
        final List<RegisteredPlayer> players = List.of(
                new RegisteredPlayer(new Deck()).setPlayer(forge),
                new RegisteredPlayer(new Deck()).setPlayer(llm));
        final Game game = new Match(new GameRules(GameType.Constructed), players, "Engine selection").createGame();
        final Player forgePlayer = game.getPlayers().get(0);
        final Player llmPlayer = game.getPlayers().get(1);

        Assert.assertEquals(forgePlayer.getController().getClass(), PlayerControllerAi.class);
        Assert.assertTrue(((PlayerControllerAi) forgePlayer.getController()).getAi().usesFullSimulation());
        Assert.assertTrue(llmPlayer.getController() instanceof PlayerControllerLlm);
        Assert.assertTrue(llmPlayer.getController().isAI());
        Assert.assertNull(llmPlayer.getController().chooseSpellAbilityToPlay(), "The scaffold passes priority");
        Assert.assertTrue(llm.createMindSlaveController(llmPlayer, forgePlayer) instanceof PlayerControllerLlm);
    }
}
