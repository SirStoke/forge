package forge.ai.llm;

import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/** Medium: post-block sacrifices, trample, and a real Mortal Combat upkeep trigger. */
public class WelcomeToThePitEval extends PuzzleEvalSupport {
    @BeforeClass
    public void validateReferenceSolution() {
        new PuzzleFixtureChecks().winThePitUsingPostBlockSacrificesAndTheRealUpkeepTrigger();
    }

    static Scenario position() {
        final Scenario s = new Scenario("Welcome to the Pit", 1, 20);
        // Fifteen existing creature cards plus the five creatures in play reach Mortal Combat's twenty.
        s.put(s.player, ZoneType.Graveyard, "Walking Corpse", 4);
        s.put(s.player, ZoneType.Graveyard, "Scathe Zombies", 4);
        s.put(s.player, ZoneType.Graveyard, "Barony Vampire", 4);
        s.put(s.player, ZoneType.Graveyard, "Warpath Ghoul", 3);
        s.put(s.player, ZoneType.Battlefield, "Mortal Combat");
        s.put(s.player, ZoneType.Battlefield, "Bloodthrone Vampire", 2);
        s.put(s.player, ZoneType.Battlefield, "Death Cultist");
        s.put(s.player, ZoneType.Battlefield, "Lord of the Pit");
        s.put(s.player, ZoneType.Battlefield, "Mortivore").setTapped(true);
        s.fillLibrary(s.player, "Swamp");
        final Card colossus = s.put(s.opponent, ZoneType.Battlefield, "Colossus of Sardia");
        final Card firstWurm = s.put(s.opponent, ZoneType.Battlefield, "Craw Wurm");
        final Card secondWurm = s.put(s.opponent, ZoneType.Battlefield, "Craw Wurm");
        final Card goblin = s.put(s.opponent, ZoneType.Battlefield, "Raging Goblin");
        s.fillLibrary(s.opponent, "Forest");
        s.prepare(PhaseType.COMBAT_DECLARE_BLOCKERS, s.opponent);
        final Combat combat = new Combat(s.opponent);
        s.game.getPhaseHandler().setCombat(combat);
        for (Card attacker : new Card[] {colossus, firstWurm, secondWurm, goblin}) {
            combat.addAttacker(attacker, s.player);
            attacker.setTapped(true);
        }
        return s;
    }

    @Test(timeOut = 900_000)
    public void surviveAndWinOnTheNextUpkeepThroughAcp() {
        try (Scenario s = position()) {
            s.startLlms();
            s.declareBlocks();
            s.run(() -> s.game.getPhaseHandler().getTurn() > 2
                    || s.game.getPhaseHandler().is(PhaseType.DRAW, s.player));
            Assert.assertEquals(s.player.getOutcome().altWinSourceName, "Mortal Combat",
                    "The actual enchantment trigger must win the game");
            Assert.assertTrue(s.player.getCardsIn(ZoneType.Graveyard).stream().filter(Card::isCreature).count() >= 20);
        }
    }
}
