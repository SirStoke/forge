package forge.ai.llm;

import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;
import org.testng.annotations.Test;

/** Easy: both players use their normal LLM controllers from a prepared combat position. */
public class RumbleInTheJungleEval extends PuzzleEvalSupport {
    @Test(timeOut = 900_000)
    public void surviveTheTramplerAndAttackForLethalThroughCodexAcp() {
        try (Scenario s = new Scenario("Rumble in the Jungle", 2, 2)) {
            s.put(s.player, ZoneType.Battlefield, "Runeclaw Bear");
            s.put(s.player, ZoneType.Battlefield, "Barony Vampire");
            s.put(s.player, ZoneType.Battlefield, "Gloomhunter");
            s.fillLibrary(s.player, "Swamp");
            final var cub = s.put(s.opponent, ZoneType.Battlefield, "Stomper Cub");
            s.fillLibrary(s.opponent, "Forest");
            s.prepare(PhaseType.COMBAT_DECLARE_BLOCKERS, s.opponent);
            final Combat combat = new Combat(s.opponent);
            s.game.getPhaseHandler().setCombat(combat);
            combat.addAttacker(cub, s.player);
            cub.setTapped(true);
            s.startLlms();
            s.declareBlocks();
            s.run(() -> s.game.getPhaseHandler().getTurn() > 2
                    || s.game.getPhaseHandler().is(PhaseType.CLEANUP, s.player));
        }
    }
}
