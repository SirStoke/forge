package forge.ai.llm;

import forge.game.player.Player;

import java.util.List;

/** Captured on the game thread; tool readers never follow references into a game or match. */
record PreviousGameLogSnapshot(boolean available, String winner, List<String> entries) {
    PreviousGameLogSnapshot {
        entries = List.copyOf(entries);
    }

    static PreviousGameLogSnapshot capture(Player viewer) {
        final var match = viewer.getGame().getMatch();
        final var outcome = match.getLastOutcome();
        final var winner = outcome == null ? null : outcome.getWinningLobbyPlayer();
        return new PreviousGameLogSnapshot(outcome != null, winner == null ? null : winner.getName(),
                match.getLastGameLog());
    }

    Page page(int offset, int limit) {
        final int start = Math.min(offset, entries.size());
        final int end = start + Math.min(limit, entries.size() - start);
        return new Page(available, winner, entries.size(), offset, entries.subList(start, end));
    }

    record Page(boolean available, String winner, int totalEntries, int offset, List<String> entries) { }
}
