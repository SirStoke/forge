package forge.ai.llm;

import forge.CardStorageReader;
import forge.StaticData;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.CardFactory;
import forge.game.event.GameEventGameFinished;
import forge.game.event.GameEventGameStarted;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.util.Lang;
import forge.util.Localizer;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;

/** Live ACP evals; run with the llm-evals Maven profile, never the normal test command. */
public class MulliganEval {
    @BeforeClass
    public void initializeCardDatabase() {
        Localizer.getInstance().initialize("en-US", "res/languages/");
        Lang.createInstance("en-US");
        // Keep the normal card-lookup MCP tool available without initializing a GUI or user preferences.
        new StaticData(new CardStorageReader("res/cardsfolder", null, true), null,
                "res/editions", "target/llm-evals/custom-editions", "res/blockdata", "latest", false, false);
    }

    private PaperCard card(String name) {
        final PaperCard card = StaticData.instance().getCommonCards().getCard(name);
        Assert.assertNotNull(card, "Missing eval card: " + name);
        return card;
    }

    private Deck blueDeck() {
        final Deck deck = new Deck("Mono-blue creatures");
        deck.getMain().add(card("Island"), 24);
        for (String name : List.of("Merfolk of the Pearl Trident", "Lord of Atlantis", "Silvergill Adept",
                "Merfolk Looter", "Coral Merfolk", "Wind Drake", "Air Elemental", "Counterspell", "Opt")) {
            deck.getMain().add(card(name), 4);
        }
        return deck;
    }

    @Test(timeOut = 600_000)
    public void mulliganSevenIslandsThroughAcp() {
        final Game game = new Match(new GameRules(GameType.Constructed), List.of(
                new RegisteredPlayer(blueDeck()).setPlayer(new LobbyPlayerAi("LLM", null)),
                new RegisteredPlayer(blueDeck()).setPlayer(new LobbyPlayerAi("Opponent", null))),
                "Seven Islands mulligan eval").createGame();
        final var player = game.getPlayers().get(0);
        for (var participant : game.getPlayers()) {
            participant.setStartingLife(20);
        }
        // Fix the opening hand and populate the remaining library without shuffling or playing a full game.
        for (var entry : player.getRegisteredPlayer().getDeck().getMain()) {
            for (int copy = 0; copy < entry.getValue(); copy++) {
                final var card = CardFactory.getCard(entry.getKey(), player, game);
                final ZoneType zone = card.getName().equals("Island") && player.getCardsIn(ZoneType.Hand).size() < 7
                        ? ZoneType.Hand : ZoneType.Library;
                player.getZone(zone).add(card);
            }
        }
        game.setAge(GameStage.Mulligan);
        final PlayerControllerLlm controller = new PlayerControllerLlm(game, player, player.getLobbyPlayer(),
                LlmEvalConfiguration.fromSystemProperties().agentFactory(player)) {
            @Override
            protected boolean fallbackMulliganKeepHand(forge.game.player.Player firstPlayer, int cardsToReturn) {
                throw new AssertionError("The LLM must submit submit_mulligan_decision through MCP; "
                        + "local fallback cannot pass this eval. Check the ACP conversation in the test output.");
            }
        };
        player.dangerouslySetController(controller);
        try {
            Assert.assertEquals(player.getCardsIn(ZoneType.Hand).size(), 7);
            Assert.assertTrue(player.getCardsIn(ZoneType.Hand).stream().allMatch(c -> c.getName().equals("Island")));
            game.fireEvent(new GameEventGameStarted(GameType.Constructed, player, game.getPlayers()));
            Assert.assertNotNull(controller.decisionAgent(),
                    "Real ACP agent startup failed; check launch prerequisites, agent authentication, network access, and the logs");
            Assert.assertFalse(controller.mulliganKeepHand(player, 0),
                    "The LLM should mulligan an opening hand of seven Islands");
        } finally {
            game.fireEvent(new GameEventGameFinished());
        }
    }
}
