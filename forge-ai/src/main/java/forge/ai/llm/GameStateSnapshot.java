package forge.ai.llm;

import forge.card.MagicColor;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Public game information captured on the game thread; never includes hidden hands or library contents. */
public record GameStateSnapshot(int viewerId, int turn, String phase, Integer turnPlayerId,
                                Integer priorityPlayerId, List<PlayerState> players, CombatState combat) {
    public GameStateSnapshot {
        players = List.copyOf(players);
    }

    static GameStateSnapshot capture(Player viewer) {
        final var game = viewer.getGame();
        final var phase = game.getPhaseHandler();
        final var combat = game.getCombat();
        final CombatState combatState = combat == null ? null : new CombatState(combat.getAttackingPlayer().getId(),
                combat.getAttackers().stream().map(attacker -> {
                    final var defender = combat.getDefenderByAttacker(attacker);
                    return new Attack(attacker.getId(), defender.getId(), defender instanceof Player ? "player" : "card",
                            combat.isBlocked(attacker), combat.getBlockers(attacker).stream().map(card -> card.getId()).toList());
                }).toList());
        return new GameStateSnapshot(viewer.getId(), phase.getTurn(),
                phase.getPhase() == null ? null : phase.getPhase().name(),
                phase.getPlayerTurn() == null ? null : phase.getPlayerTurn().getId(),
                phase.getPriorityPlayer() == null ? null : phase.getPriorityPlayer().getId(),
                game.getPlayers().stream().map(player -> PlayerState.capture(player, viewer)).toList(), combatState);
    }

    public record PlayerState(int id, String name, int team, int life, String counters, Map<String, Integer> mana,
                              int handSize, int librarySize, int landsPlayedThisTurn,
                              CardsSnapshot graveyard, CardsSnapshot exile) {
        public PlayerState {
            mana = Map.copyOf(mana);
        }

        private static PlayerState capture(Player player, Player viewer) {
            final Map<String, Integer> mana = new TreeMap<>();
            for (var unit : player.getManaPool()) mana.merge(MagicColor.toShortString(unit.getColor()), 1, Integer::sum);
            return new PlayerState(player.getId(), player.getName(), player.getTeam(), player.getLife(),
                    player.getCounters().toString(), mana, player.getCardsIn(ZoneType.Hand).size(),
                    player.getCardsIn(ZoneType.Library).size(), player.getLandsPlayedThisTurn(),
                    CardsSnapshot.zone(player, ZoneType.Graveyard, viewer), CardsSnapshot.zone(player, ZoneType.Exile, viewer));
        }
    }

    public record CombatState(int attackingPlayerId, List<Attack> attacks) {
        public CombatState {
            attacks = List.copyOf(attacks);
        }
    }

    public record Attack(int attackerId, int defenderId, String defenderType, boolean blocked, List<Integer> blockerIds) {
        public Attack {
            blockerIds = List.copyOf(blockerIds);
        }
    }
}
