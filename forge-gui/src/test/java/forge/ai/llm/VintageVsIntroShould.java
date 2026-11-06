package forge.ai.llm;

import forge.StaticData;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.event.GameEventGameFinished;
import forge.util.MyRandom;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Random;

/** Offline fixture check: run the same shuffled full game with classic controllers, without starting ACP. */
public class VintageVsIntroShould extends PuzzleEvalSupport {
    @Test(timeOut = 60_000)
    public void playTheShuffledDecksFromTheOpeningHandToANaturalWin() {
        final Random previousRandom = MyRandom.getRandom();
        MyRandom.setRandom(new Random(VintageVsIntroEval.DEFAULT_SEED));
        Game game = null;
        try {
            final var match = VintageVsIntroEval.matchup();
            game = match.createGame();
            final var strongPlayer = game.getPlayers().get(0).getLobbyPlayer();
            Assert.assertNotNull(StaticData.instance().getAllTokens(), "Wurmcoil Engine's death trigger needs the token database");
            for (var player : game.getPlayers()) {
                Assert.assertEquals(player.getController().getClass(), PlayerControllerAi.class);
                Assert.assertEquals(player.getLife(), 20);
                Assert.assertEquals(player.getRegisteredPlayer().getDeck().getMain().countAll(), 60);
            }
            VintageVsIntroEval.play(match, game);
            Assert.assertTrue(game.isGameOver());
            Assert.assertFalse(game.getOutcome().isDraw(), "The engine must produce a real winner");
            Assert.assertTrue(game.getOutcome().isWinner(strongPlayer),
                    "The strong deck should have a winning baseline under the classic AI too");
            Assert.assertTrue(game.getPhaseHandler().getTurn() > 1, "This must exercise a full game, rather than a puzzle position");
        } finally {
            try {
                if (game != null) game.fireEvent(new GameEventGameFinished());
            } finally {
                MyRandom.setRandom(previousRandom);
            }
        }
    }
}
