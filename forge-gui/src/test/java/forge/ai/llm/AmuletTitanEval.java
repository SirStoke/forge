package forge.ai.llm;

import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/** Beginner: reuse a bounceland, cast Titan, tutor haste, and attack for six this turn. */
public class AmuletTitanEval extends PuzzleEvalSupport {
    @BeforeClass
    public void validateReferenceSolution() {
        new PuzzleFixtureChecks().winWithAmuletBouncelandAndAHastyTitan();
    }

    static Scenario position() {
        final Scenario s = new Scenario("Amulet Titan: win this turn", 20, 6);
        s.put(s.player, ZoneType.Battlefield, "Amulet of Vigor");
        s.put(s.player, ZoneType.Battlefield, "Forest", 2);
        // Two available mana alone cannot cast Titan; Azusa supplies the extra land plays.
        s.put(s.player, ZoneType.Battlefield, "Azusa, Lost but Seeking").setSickness(true);
        s.put(s.player, ZoneType.Hand, "Simic Growth Chamber");
        s.put(s.player, ZoneType.Hand, "Primeval Titan");
        s.put(s.player, ZoneType.Library, "Hanweir Battlements");
        s.put(s.player, ZoneType.Library, "Crumbling Vestige");
        s.fillLibrary(s.player, "Forest");
        s.fillLibrary(s.opponent, "Island");
        s.prepare(PhaseType.MAIN1, s.player);
        return s;
    }

    static void assertTitanLethal(Scenario s) {
        Assert.assertTrue(s.opponent.getLife() <= 0, "The opponent must lose to damage this turn");
        Assert.assertTrue(s.player.getCreaturesInPlay().stream()
                .anyMatch(c -> c.getName().equals("Primeval Titan") && c.isTapped()),
                "Titan must have entered and attacked this turn");
        Assert.assertEquals(s.game.getPhaseHandler().getTurn(), 1);
    }

    @Test(timeOut = 900_000)
    public void castTitanGiveItHasteAndAttackForLethalThroughAcp() {
        try (Scenario s = position()) {
            s.startLlms();
            s.run(() -> s.game.getPhaseHandler().getTurn() > 1 || s.game.getPhaseHandler().is(PhaseType.CLEANUP));
            assertTitanLethal(s);
        }
    }
}
