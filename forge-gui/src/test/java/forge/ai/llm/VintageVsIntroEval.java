package forge.ai.llm;

import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.event.GameEventGameFinished;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
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

/** Full game: a powered Vintage-style artifact deck against the classic AI's introductory green deck. */
public class VintageVsIntroEval extends PuzzleEvalSupport {
    static final long DEFAULT_SEED = 42;
    static final int MAX_TURNS = 20;
    private static final int MAX_ENGINE_STEPS = 2_000;
    private static final int MAX_PRIORITY_DECISIONS = 300;

    static Deck deck(String resource) {
        final var url = Objects.requireNonNull(VintageVsIntroEval.class.getResource("decks/" + resource),
                "Missing eval deck: " + resource);
        final Deck deck = DeckSerializer.fromSections(FileSection.parseSections(FileUtil.readFile(url)));
        Assert.assertNotNull(deck, "Could not load eval deck: " + resource);
        Assert.assertEquals(deck.getMain().countAll(), 60, "Eval decks must contain exactly 60 cards");
        return deck;
    }

    static Match matchup() {
        final GameRules rules = new GameRules(GameType.Constructed);
        rules.setGamesPerMatch(1);
        rules.setWarnAboutAICards(false);
        return new Match(rules, List.of(
                new RegisteredPlayer(deck("vintage-workshops.dck")).setPlayer(new LobbyPlayerAi("Vintage", null)),
                new RegisteredPlayer(deck("intro-green.dck")).setPlayer(new LobbyPlayerAi("Classic Intro", null))),
                "Vintage LLM vs classic introductory AI");
    }

    static void play(Match match, Game game) {
        game.setNoGUIUser();
        // Start normally: populate and shuffle both libraries, choose play/draw, draw hands and mulligan.
        // Run the ordinary engine steps inside the start hook so bounds can abort the full game.
        // On successful completion the outer main loop has nothing left to do.
        match.startGame(game, () -> {
            for (int step = 0; step < MAX_ENGINE_STEPS && !game.isGameOver(); step++) {
                Assert.assertTrue(game.getPhaseHandler().getTurn() <= MAX_TURNS,
                        "Match exceeded " + MAX_TURNS + " turns. " + diagnostics(game));
                game.getPhaseHandler().mainLoopStep();
            }
            Assert.assertTrue(game.isGameOver(), "Match exceeded " + MAX_ENGINE_STEPS + " engine steps. " + diagnostics(game));
        });
    }

    private static String diagnostics(Game game) {
        return "Turn=" + game.getPhaseHandler().getTurn() + " phase=" + game.getPhaseHandler().getPhase()
                + " players=" + game.getRegisteredPlayers().stream().map(p -> p.getName() + " life=" + p.getLife()).toList();
    }

    @Test(timeOut = 900_000)
    public void winAFullGameWithVintageAgainstTheClassicIntroAi() throws IOException {
        final long seed = Long.getLong("forge.llm.eval.seed", DEFAULT_SEED);
        final Random previousRandom = MyRandom.getRandom();
        MyRandom.setRandom(new Random(seed));
        Game game = null;
        try {
            final Match match = matchup();
            game = match.createGame();
            final Player llm = game.getPlayers().get(0);
            final Player opponent = game.getPlayers().get(1);
            final var classicController = opponent.getController();
            Assert.assertEquals(classicController.getClass(), PlayerControllerAi.class);
            llm.dangerouslySetController(new PlayerControllerLlm(game, llm, llm.getLobbyPlayer()) {
                private int priorityDecisions;

                @Override protected void onLlmFallback(String title) {
                    throw new AssertionError("A real MCP decision is required; classic fallback cannot pass this eval: " + title);
                }

                @Override protected boolean fallbackMulliganKeepHand(Player firstPlayer, int cardsToReturn) {
                    throw new AssertionError("The LLM must submit its mulligan decision through MCP");
                }

                @Override public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
                    Assert.assertTrue(usesLlm(), "Real Codex ACP startup failed; check the logs and prerequisites");
                    return super.mulliganKeepHand(firstPlayer, cardsToReturn);
                }

                @Override public List<SpellAbility> chooseSpellAbilityToPlay() {
                    Assert.assertTrue(usesLlm(), "The live LLM agent must remain active");
                    Assert.assertTrue(++priorityDecisions <= MAX_PRIORITY_DECISIONS,
                            "Match exceeded " + MAX_PRIORITY_DECISIONS + " LLM priority decisions");
                    return super.chooseSpellAbilityToPlay();
                }
            });
            Logger.info("Vintage vs intro eval: seed={}, LLM deck={}, classic deck={}", seed,
                    llm.getRegisteredPlayer().getDeck().getName(), opponent.getRegisteredPlayer().getDeck().getName());
            play(match, game);
            Assert.assertSame(opponent.getController(), classicController, "The opponent must remain controlled by the classic AI");
            Assert.assertTrue(game.getOutcome().isWinner(llm.getLobbyPlayer()),
                    "The LLM's powered Vintage deck must beat the classic AI's introductory deck. " + diagnostics(game)
                            + "\n" + game.getGameLog().getLogEntries(null));
            Logger.info("Vintage vs intro eval won by {} on turn {} (seed={})", llm.getName(),
                    game.getPhaseHandler().getTurn(), seed);
        } finally {
            try {
                if (game != null) {
                    game.fireEvent(new GameEventGameFinished());
                    final Path log = Path.of("target", "llm-evals", "vintage-vs-intro-game.log");
                    Files.createDirectories(log.getParent());
                    Files.writeString(log, "Seed: " + seed + "\n" + diagnostics(game) + "\n"
                            + String.join("\n", game.getGameLog().getAllEntries().stream().map(Object::toString).toList())
                            + "\n", StandardCharsets.UTF_8);
                }
            } finally {
                MyRandom.setRandom(previousRandom);
            }
        }
    }
}
