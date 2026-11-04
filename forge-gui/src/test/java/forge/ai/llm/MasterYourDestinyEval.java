package forge.ai.llm;

import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;
import org.testng.annotations.Test;
import org.testng.annotations.BeforeClass;

/** Difficult: plan Index, cascade, Oracle's land, Djinn's wish, and divided Hellkite damage. */
public class MasterYourDestinyEval extends PuzzleEvalSupport {
    @BeforeClass
    public void validateReferenceSolution() {
        new PuzzleFixtureChecks().winDestinyUsingIndexCascadeOracleAndTheWish();
    }

    static Scenario position() {
        final Scenario s = new Scenario("Master Your Destiny", 1, 3);
        s.put(s.player, ZoneType.Battlefield, "Island");
        s.put(s.player, ZoneType.Battlefield, "Forest", 3);
        s.put(s.player, ZoneType.Battlefield, "Mountain", 3);
        final var djinn = s.put(s.player, ZoneType.Battlefield, "Djinn of Wishes");
        djinn.setTapped(true);
        djinn.setCounters(CounterEnumType.WISH, 1);
        s.put(s.player, ZoneType.Hand, "Index");
        s.put(s.player, ZoneType.Hand, "Bloodbraid Elf");
        s.put(s.player, ZoneType.Hand, "Island");
        // Fixed initial order, top first. It is exposed to the model only by resolving Index or another legal effect.
        for (String name : new String[] {"Island", "Coiling Oracle", "Bogardan Hellkite", "Erratic Explosion", "Tranquility"}) {
            s.put(s.player, ZoneType.Library, name);
        }
        s.fillLibrary(s.player, "Mountain");
        s.put(s.opponent, ZoneType.Battlefield, "Air Elemental");
        s.put(s.opponent, ZoneType.Battlefield, "Illusionary Servant");
        s.put(s.opponent, ZoneType.Battlefield, "Cloud Elemental");
        s.put(s.opponent, ZoneType.Battlefield, "Leyline of Sanctity");
        s.fillLibrary(s.opponent, "Island");
        s.prepare(PhaseType.MAIN1, s.player);
        return s;
    }

    @Test(timeOut = 900_000)
    public void planTheLibraryAndWinThisTurnThroughCodexAcp() {
        try (Scenario s = position()) {
            s.startLlms();
            s.run(() -> s.game.getPhaseHandler().getTurn() > 1 || s.game.getPhaseHandler().is(PhaseType.CLEANUP));
        }
    }
}
