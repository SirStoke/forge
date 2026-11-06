package forge.ai.llm;

import forge.StaticData;
import forge.ai.LobbyPlayerAi;
import forge.ai.llm.tools.ObservePreviousGameLog;
import forge.ai.llm.tools.SubmitSideboardPlan;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.event.GameEventGameFinished;
import forge.game.event.GameEventPlayerDamaged;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventSpellResolved;
import forge.game.phase.PhaseType;
import forge.game.player.GameLossReason;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Live ACP sideboarding eval: infer the matchup from a fixed replay of a lost game, without a matchup hint.
 * Run with: mvn -pl forge-gui -am -Pllm-evals -Dtest=SideboardingEval -Dsurefire.failIfNoSpecifiedTests=false test
 */
public class SideboardingEval extends PuzzleEvalSupport {
    private static final Set<String> ARTIFACT_ANSWERS = Set.of("Nature's Claim", "Creeping Corrosion");

    private PaperCard card(String name) {
        final var card = StaticData.instance().getCommonCards().getCard(name);
        Assert.assertNotNull(card, "Missing eval card: " + name);
        return card;
    }

    private Deck deck() {
        final var deck = new Deck("Mono-green creatures");
        deck.getMain().add(card("Forest"), 24);
        for (String name : List.of("Llanowar Elves", "Elvish Mystic", "Grizzly Bears", "Leatherback Baloth",
                "Llanowar Visionary", "Garruk's Companion", "Colossal Dreadmaw", "Giant Growth", "Fog")) {
            deck.getMain().add(card(name), 4);
        }
        // Interleave answers to several matchups; neither the menu order nor the prompt identifies the right cards.
        for (String name : List.of("Feed the Clan", "Nature's Claim", "Tormod's Crypt",
                "Obstinate Baloth", "Creeping Corrosion")) {
            deck.getOrCreate(DeckSection.Sideboard).add(card(name), 3);
        }
        return deck;
    }

    private Deck opponentDeck() {
        final var deck = new Deck("Opponent");
        deck.getMain().add(card("Wastes"), 24);
        for (String name : List.of("Mind Stone", "Hedron Archive", "Wurmcoil Engine", "Steel Hellkite",
                "Myr Battlesphere", "Ornithopter", "Foundry Inspector", "Everflowing Chalice", "Guardian Idol")) {
            deck.getMain().add(card(name), 4);
        }
        return deck;
    }

    private Card replaySpell(Game game, Player caster, String name) {
        final var permanent = CardFactory.getCard(card(name), caster, game);
        final var spell = permanent.getFirstSpellAbility();
        Assert.assertNotNull(spell);
        spell.setActivatingPlayer(caster);
        game.fireEvent(new GameEventSpellAbilityCast(spell, new SpellAbilityStackInstance(spell), 0));
        caster.getZone(ZoneType.Battlefield).add(permanent);
        game.fireEvent(new GameEventSpellResolved(spell, false));
        return permanent;
    }

    private void replayLoss(Match match) {
        // Replay only public casts/resolutions and combat damage. This eval measures the between-game decision,
        // so a fixed transcript replaces the cost and nondeterminism of having an LLM play an entire first game.
        final var previous = match.createGame();
        final var player = previous.getPlayers().get(0);
        final var opponent = previous.getPlayers().get(1);
        player.setStartingLife(20);
        opponent.setStartingLife(20);
        previous.setAge(GameStage.Play);
        previous.getPhaseHandler().devModeSet(PhaseType.MAIN1, opponent, false, 4);
        final var wurmcoil = replaySpell(previous, opponent, "Wurmcoil Engine");
        final var hellkite = replaySpell(previous, opponent, "Steel Hellkite");
        for (Card source : List.of(wurmcoil, hellkite, wurmcoil, hellkite)) {
            final int damage = source.getName().equals("Wurmcoil Engine") ? 6 : 5;
            previous.fireEvent(new GameEventPlayerDamaged(player.getView(), source.getView(), damage, true, false));
            player.setLife(player.getLife() - damage, null);
        }
        Assert.assertTrue(player.getLife() <= 0);
        player.loseConditionMet(GameLossReason.LifeReachedZero, null);
        previous.setGameOver(GameEndReason.AllOpposingTeamsLost);
        Assert.assertTrue(match.getLastOutcome().isWinner(opponent.getLobbyPlayer()));
        Assert.assertTrue(match.getLastGameLog().stream().anyMatch(line -> line.contains("Wurmcoil Engine")));
        Assert.assertTrue(match.getLastGameLog().stream().anyMatch(line -> line.contains("Steel Hellkite")));
        previous.fireEvent(new GameEventGameFinished());
    }

    @Test(timeOut = 600_000)
    public void readThePreviousLossAndBoardInArtifactAnswersThroughCodexAcp() throws Exception {
        final var rules = new GameRules(GameType.Constructed);
        rules.setGamesPerMatch(3);
        rules.setAISideboardingEnabled(true);
        final var match = new Match(rules, List.of(
                new RegisteredPlayer(deck()).setPlayer(new LobbyPlayerAi("Codex", null)),
                new RegisteredPlayer(opponentDeck()).setPlayer(new LobbyPlayerAi("Opponent", null))), "Sideboarding eval");
        replayLoss(match);
        final var next = match.createGame();
        final var player = next.getPlayers().get(0);
        final var deck = player.getRegisteredPlayer().getDeck();
        final var poolBefore = new CardPool();
        poolBefore.addAll(deck.getMain());
        poolBefore.addAll(deck.get(DeckSection.Sideboard));
        final String previousFlag = System.getProperty("forge.llm.profile");
        System.setProperty("forge.llm.profile", "true");
        final var profile = LlmMatchProfile.forGame(next);
        final var controller = new PlayerControllerLlm(next, player, player.getLobbyPlayer()) {
            @Override protected void onLlmFallback(String title) {
                throw new AssertionError("A real MCP submission is required; fallback cannot pass this eval: " + title);
            }
        };
        player.dangerouslySetController(controller);
        try {
            // No game-start event: sideboarding must initialize the session before opening hands exist.
            final List<PaperCard> main = controller.sideboard(deck, GameType.Constructed, player.getName());
            Assert.assertNotNull(main, "Codex must submit a sideboard plan through MCP");
            final long answers = main.stream().filter(c -> ARTIFACT_ANSWERS.contains(c.getName())).count();
            Assert.assertTrue(answers > 0,
                    "Codex must board in Nature's Claim or Creeping Corrosion after losing to artifacts. Plan: "
                            + controller.getSideboardingReport());
            Assert.assertTrue(main.size() >= 60 && poolBefore.countAll() - main.size() <= 15);
            final var remaining = new CardPool();
            remaining.addAll(poolBefore);
            for (var card : main) {
                Assert.assertTrue(remaining.contains(card), "The plan cannot create extra cards: " + card);
                remaining.remove(card, 1);
            }
            System.out.println("Sideboarding eval accepted: " + controller.getSideboardingReport());
        } finally {
            next.fireEvent(new GameEventGameFinished());
            if (previousFlag == null) System.clearProperty("forge.llm.profile");
            else System.setProperty("forge.llm.profile", previousFlag);
        }
        Assert.assertNotNull(profile.path(), "The eval must record tool usage");
        final var records = Files.readAllLines(profile.path()).stream().map(line -> {
            try { return McpJsonDefaults.getMapper().readValue(line, Map.class); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }).toList();
        // Profiles split a tool's name and its result across span_start/span_end records with the same id.
        Assert.assertTrue(records.stream().anyMatch(start -> "tool".equals(start.get("kind"))
                        && ObservePreviousGameLog.NAME.equals(start.get("name"))
                        && records.stream().anyMatch(end -> start.get("id").equals(end.get("id"))
                                && "span_end".equals(end.get("event")) && "ok".equals(end.get("outcome")))),
                "Codex must successfully read the previous game's log through MCP");
        Assert.assertTrue(records.stream().anyMatch(record -> "decision_accepted".equals(record.get("event"))
                        && SubmitSideboardPlan.NAME.equals(record.get("tool"))),
                "Codex must submit the plan through the sideboarding tool");
    }
}
