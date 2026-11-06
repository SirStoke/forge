package forge.ai.llm;

import forge.CardStorageReader;
import forge.StaticData;
import forge.card.CardType;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.event.GameEventGameFinished;
import forge.game.event.GameEventGameStarted;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.util.Lang;
import forge.util.Localizer;
import forge.util.FileSection;
import forge.util.FileUtil;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;

import java.util.List;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;

/** Test-only fixtures and bounded execution of Forge's real priority, stack and phase loop. */
abstract class PuzzleEvalSupport {
    @BeforeClass
    public void initializeCardDatabase() {
        Localizer.getInstance().initialize("en-US", "res/languages/");
        Lang.createInstance("en-US");
        // Load the same type definitions as the app, without initializing GUI/profile-dependent constants.
        if (!CardType.Constant.LOADED.isSet()) {
            var sections = FileSection.parseSections(FileUtil.readFile("res/lists/TypeLists.txt"));
            sections.forEach(CardType.Helper::parseTypes);
            CardType.Constant.LOADED.set();
        }
        // Full games can resolve token-producing cards such as Wurmcoil Engine.
        new StaticData(new CardStorageReader("res/cardsfolder", null, true),
                new CardStorageReader("res/tokenscripts", null, false), null, null,
                "res/editions", "target/llm-evals/custom-editions", "res/blockdata", "", "latest",
                false, false, false, false);
    }

    static final class Scenario implements AutoCloseable {
        final Game game;
        final Player player;
        final Player opponent;

        Scenario(String name, int life, int opponentLife) {
            game = new Match(new GameRules(GameType.Constructed), List.of(
                    new RegisteredPlayer(new Deck("Deck")).setPlayer(new LobbyPlayerAi("One", null)),
                    new RegisteredPlayer(new Deck("Deck")).setPlayer(new LobbyPlayerAi("Two", null))), name)
                    .createGame();
            player = game.getPlayers().get(0);
            opponent = game.getPlayers().get(1);
            player.setStartingLife(life);
            opponent.setStartingLife(opponentLife);
            game.setAge(GameStage.Play);
        }

        Card put(Player owner, ZoneType zone, String name) {
            final PaperCard paper = StaticData.instance().getCommonCards().getCard(name);
            Assert.assertNotNull(paper, "Missing puzzle card: " + name);
            owner.getRegisteredPlayer().getDeck().getMain().add(paper);
            final Card card = CardFactory.getCard(paper, owner, game);
            owner.getZone(zone).add(card);
            if (zone == ZoneType.Battlefield) card.setSickness(false);
            return card;
        }

        void put(Player owner, ZoneType zone, String name, int copies) {
            for (int i = 0; i < copies; i++) put(owner, zone, name);
        }

        void fillLibrary(Player owner, String land) {
            while (owner.getRegisteredPlayer().getDeck().getMain().countAll() < 60) {
                put(owner, ZoneType.Library, land);
            }
        }

        void startLlms() {
            final List<PlayerControllerLlm> controllers = new ArrayList<>();
            for (Player participant : game.getPlayers()) {
                final PlayerControllerLlm controller = new PlayerControllerLlm(game, participant, participant.getLobbyPlayer()) {
                    private int priorityDecisions;

                    @Override protected void onLlmFallback(String title) {
                        throw new AssertionError("A real MCP submission is required; local fallback cannot pass this eval: " + title);
                    }

                    @Override public List<SpellAbility> chooseSpellAbilityToPlay() {
                        Assert.assertTrue(usesLlm(), "The live agent must remain active");
                        Assert.assertTrue(++priorityDecisions <= 80, "Puzzle exceeded 80 priority decisions");
                        return super.chooseSpellAbilityToPlay();
                    }
                };
                participant.dangerouslySetController(controller);
                controllers.add(controller);
            }
            game.fireEvent(new GameEventGameStarted(GameType.Constructed, game.getPhaseHandler().getPlayerTurn(), game.getPlayers()));
            for (PlayerControllerLlm controller : controllers) {
                Assert.assertNotNull(controller.decisionAgent(), "Real Codex ACP startup failed; check the logs and prerequisites");
            }
        }

        void prepare(PhaseType phase, Player active) {
            game.getPhaseHandler().devModeSet(phase, active, false, 1);
            game.getAction().checkStaticAbilities();
            game.getTriggerHandler().resetActiveTriggers();
            game.copyLastState();
        }

        void run(BooleanSupplier deadline) {
            // devModeSet skips phase entry; enable priority at the already prepared puzzle position.
            game.getPhaseHandler().onStackResolved();
            for (int step = 0; step < 250 && !game.isGameOver(); step++) {
                if (deadline.getAsBoolean()) {
                    Assert.fail("Puzzle deadline reached without winning. Phase=" + game.getPhaseHandler().getPhase()
                            + " hand=" + player.getCardsIn(ZoneType.Hand)
                            + " battlefield=" + game.getCardsIn(ZoneType.Battlefield)
                            + " opponent life=" + opponent.getLife() + "\n" + game.getGameLog().getLogEntries(null));
                }
                game.getPhaseHandler().mainLoopStep();
            }
            Assert.assertTrue(game.isGameOver(), "Puzzle exceeded 250 engine steps");
            Assert.assertTrue(game.getOutcome().isWinner(player.getLobbyPlayer()), "The puzzle player must win");
        }

        Combat declareBlocks() {
            final Combat combat = game.getCombat();
            player.getController().declareBlockers(player, combat);
            Assert.assertNull(forge.game.combat.CombatUtil.validateBlocks(combat, player), "Blocks must be legal");
            combat.orderBlockersForDamageAssignment();
            combat.orderAttackersForDamageAssignment();
            // Lock in blocked status before any blockers can be sacrificed during priority.
            combat.fireTriggersForUnblockedAttackers(game);
            return combat;
        }

        @Override public void close() {
            game.fireEvent(new GameEventGameFinished());
        }
    }
}
