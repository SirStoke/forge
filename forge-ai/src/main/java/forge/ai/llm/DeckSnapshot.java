package forge.ai.llm;

import forge.deck.DeckSection;
import forge.game.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Immutable registered deck composition; never reads the live library or its order. */
public record DeckSnapshot(String name, List<Section> sections) {
    public DeckSnapshot {
        sections = List.copyOf(sections);
    }

    static DeckSnapshot capture(Player viewer) {
        final var deck = viewer.getRegisteredPlayer().getDeck();
        final List<Section> sections = new ArrayList<>();
        for (DeckSection section : DeckSection.values()) {
            final var pool = deck.get(section);
            if (pool == null) continue;
            // Merge different printings/finishes of the same card and use stable alphabetical order.
            final Map<String, Integer> counts = new TreeMap<>();
            for (var entry : pool) {
                counts.merge(entry.getKey().getName(), entry.getValue(), Integer::sum);
            }
            final List<CardCount> cards = counts.entrySet().stream()
                    .map(entry -> new CardCount(entry.getKey(), entry.getValue())).toList();
            sections.add(new Section(section.name(), cards.stream().mapToInt(CardCount::count).sum(), cards));
        }
        return new DeckSnapshot(deck.getName(), sections);
    }

    public record Section(String name, int totalCards, List<CardCount> cards) {
        public Section {
            cards = List.copyOf(cards);
        }
    }

    public record CardCount(String name, int count) { }
}
