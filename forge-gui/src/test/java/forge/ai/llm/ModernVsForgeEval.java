package forge.ai.llm;

import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.event.GameEventGameFinished;
import forge.game.player.RegisteredPlayer;
import forge.util.FileSection;
import forge.util.FileUtil;
import forge.util.MyRandom;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.tinylog.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * One full Modern game: LLM Burn against Forge Boros Energy. Either player may win.
 * Run with: mvn -pl forge-gui -am -Pllm-evals -Dtest=ModernVsForgeEval -Dsurefire.failIfNoSpecifiedTests=false test
 */
public class ModernVsForgeEval extends PuzzleEvalSupport {
    static final long DEFAULT_SEED = 42;

    private static Deck deck(String resource) {
        final var url = Objects.requireNonNull(ModernVsForgeEval.class.getResource("decks/" + resource),
                "Missing eval deck: " + resource);
        return DeckSerializer.fromSections(FileSection.parseSections(FileUtil.readFile(url)));
    }

    static Match matchup() {
        final GameRules rules = new GameRules(GameType.Constructed);
        rules.setGamesPerMatch(1);
        rules.setWarnAboutAICards(false);
        return new Match(rules, List.of(
                new RegisteredPlayer(deck("modern-burn.dck")).setPlayer(new LobbyPlayerAi("LLM Burn", null)),
                new RegisteredPlayer(deck("modern-boros-energy.dck")).setPlayer(new LobbyPlayerAi("Forge Boros Energy", null))),
                "Modern LLM vs Forge AI");
    }

    @Test(timeOut = 900_000)
    public void finishAModernGameWithAWinner() throws IOException {
        final long seed = Long.getLong("forge.llm.eval.seed", DEFAULT_SEED);
        final Random previousRandom = MyRandom.getRandom();
        MyRandom.setRandom(new Random(seed));
        Game game = null;
        try {
            final Match match = matchup();
            game = match.createGame();
            final var llm = game.getPlayers().get(0);
            final var opponent = game.getPlayers().get(1);
            llm.dangerouslySetController(new PlayerControllerLlm(game, llm, llm.getLobbyPlayer(),
                    LlmEvalConfiguration.fromSystemProperties().agentFactory(llm)));
            game.setNoGUIUser();
            Logger.info("Modern eval: seed={}, LLM deck={}, Forge deck={}", seed,
                    llm.getRegisteredPlayer().getDeck().getName(), opponent.getRegisteredPlayer().getDeck().getName());
            match.startGame(game);
            final var outcome = game.getOutcome();
            Assert.assertTrue(outcome != null && (outcome.isWinner(llm.getLobbyPlayer())
                            || outcome.isWinner(opponent.getLobbyPlayer())),
                    "One of the two players must win.\n" + game.getGameLog().getLogEntries(null));
            Logger.info("Modern eval won by {} on turn {} (seed={})", outcome.getWinningPlayer().getPlayer().getName(),
                    game.getPhaseHandler().getTurn(), seed);
        } finally {
            try {
                if (game != null) {
                    game.fireEvent(new GameEventGameFinished());
                    final Path log = Path.of("target", "llm-evals", "modern-vs-forge-game.log");
                    Files.createDirectories(log.getParent());
                    Files.writeString(log, "Seed: " + seed + "\n"
                            + String.join("\n", game.getGameLog().getAllEntries().stream().map(Object::toString).toList())
                            + "\n", StandardCharsets.UTF_8);
                }
            } finally {
                MyRandom.setRandom(previousRandom);
            }
        }
    }
}
