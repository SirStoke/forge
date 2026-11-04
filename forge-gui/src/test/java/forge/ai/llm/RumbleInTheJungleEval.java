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
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.event.GameEventGameFinished;
import forge.game.event.GameEventGameStarted;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.util.Lang;
import forge.util.Localizer;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Adapted from Duels of the Planeswalkers 2012's beginner challenge; see LLM-Evals.md for the source. */
public class RumbleInTheJungleEval {
    @BeforeClass
    public void initializeCardDatabase() {
        Localizer.getInstance().initialize("en-US", "res/languages/");
        Lang.createInstance("en-US");
        new StaticData(new CardStorageReader("res/cardsfolder", null, true), null,
                "res/editions", "target/llm-evals/custom-editions", "res/blockdata", "latest", false, false);
    }

    private PaperCard card(String name) {
        final PaperCard card = StaticData.instance().getCommonCards().getCard(name);
        Assert.assertNotNull(card, "Missing puzzle card: " + name);
        return card;
    }

    private Deck deck(String land, String... creatures) {
        final Deck deck = new Deck("Rumble in the Jungle");
        for (String name : creatures) deck.getMain().add(card(name));
        deck.getMain().add(card(land), 60 - creatures.length);
        return deck;
    }

    private void populate(Player player) {
        for (var entry : player.getRegisteredPlayer().getDeck().getMain()) {
            for (int copy = 0; copy < entry.getValue(); copy++) {
                final Card card = CardFactory.getCard(entry.getKey(), player, player.getGame());
                player.getZone(card.isCreature() ? ZoneType.Battlefield : ZoneType.Library).add(card);
                card.setSickness(false);
            }
        }
    }

    private Card creature(Player player, String name) {
        return player.getCreaturesInPlay().stream().filter(card -> card.getName().equals(name))
                .findFirst().orElseThrow();
    }

    private void resolveCombat(Game game, Combat combat, int turn) {
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DAMAGE, combat.getAttackingPlayer(), false, turn);
        combat.orderBlockersForDamageAssignment();
        combat.orderAttackersForDamageAssignment();
        combat.fireTriggersForUnblockedAttackers(game);
        Assert.assertTrue(combat.assignCombatDamage(false), "The declared combat must assign damage");
        combat.dealAssignedDamage();
        game.getAction().checkStateEffects(true);
    }

    @Test(timeOut = 600_000)
    public void surviveTheTramplerAndAttackForLethalThroughCodexAcp() {
        final Game game = new Match(new GameRules(GameType.Constructed), List.of(
                new RegisteredPlayer(deck("Swamp", "Runeclaw Bear", "Barony Vampire", "Gloomhunter"))
                        .setPlayer(new LobbyPlayerAi("Codex", null)),
                new RegisteredPlayer(deck("Forest", "Stomper Cub"))
                        .setPlayer(new LobbyPlayerAi("Garruk", null))), "Rumble in the Jungle eval").createGame();
        final Player player = game.getPlayers().get(0);
        final Player opponent = game.getPlayers().get(1);
        for (Player participant : game.getPlayers()) {
            participant.setStartingLife(2);
            populate(participant);
        }
        final Card bear = creature(player, "Runeclaw Bear");
        final Card vampire = creature(player, "Barony Vampire");
        final Card bat = creature(player, "Gloomhunter");
        final Card cub = creature(opponent, "Stomper Cub");
        final PlayerControllerLlm controller = new PlayerControllerLlm(game, player, player.getLobbyPlayer()) {
            @Override
            protected void onLlmFallback(String title) {
                throw new AssertionError("Codex must submit the puzzle decision through MCP; local fallback "
                        + "cannot pass this eval. Unanswered decision: " + title);
            }
        };
        player.dangerouslySetController(controller);
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, opponent, false, 1);
        final Combat defense = new Combat(opponent);
        game.getPhaseHandler().setCombat(defense);
        defense.addAttacker(cub, player);
        cub.setTapped(true);

        try {
            game.fireEvent(new GameEventGameStarted(GameType.Constructed, opponent, game.getPlayers()));
            Assert.assertNotNull(controller.decisionAgent(),
                    "Real Codex ACP startup failed; check pnpm, Codex authentication, network access, and the logs");
            controller.declareBlockers(player, defense);
            Assert.assertNull(CombatUtil.validateBlocks(defense, player), "Codex must submit legal blocks");
            Assert.assertEquals(new HashSet<>(defense.getBlockers(cub)), Set.of(bear, vampire),
                    "Double-block Stomper Cub with the ground creatures and preserve Gloomhunter");

            resolveCombat(game, defense, 1);
            Assert.assertFalse(game.isGameOver(), "Codex must survive the opponent's combat");
            Assert.assertTrue(player.getLife() > 0, "Trample damage must not be lethal");
            Assert.assertTrue(cub.isInZone(ZoneType.Graveyard), "The blockers must kill Stomper Cub");
            Assert.assertTrue(bat.isInZone(ZoneType.Battlefield), "Gloomhunter must survive to attack");
            Assert.assertFalse(bat.isTapped());

            // Advance to the next combat: no spells are available and the libraries contain only basic lands.
            game.getPhaseHandler().endCombat();
            game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, player, false, 2);
            final Combat attack = new Combat(player);
            game.getPhaseHandler().setCombat(attack);
            attack.initConstraints();
            controller.declareAttackers(player, attack);
            Assert.assertTrue(CombatUtil.validateAttackers(attack), "Codex must submit a legal attack");
            Assert.assertTrue(attack.isAttacking(bat, opponent), "Attack the opponent with Gloomhunter for lethal");
            opponent.getController().declareBlockers(opponent, attack);
            resolveCombat(game, attack, 2);
            Assert.assertTrue(game.isGameOver(), "The attack must win the game");
            Assert.assertTrue(game.getOutcome().isWinner(player.getLobbyPlayer()), "Codex must win the puzzle");
        } finally {
            game.fireEvent(new GameEventGameFinished());
        }
    }
}
