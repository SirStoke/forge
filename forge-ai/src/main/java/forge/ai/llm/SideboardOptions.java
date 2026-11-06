package forge.ai.llm;

import forge.ai.llm.tools.SubmitSideboardPlan;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.GameType;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.List;

/** Frozen menus and bounds for one request. Only apply() resolves options to PaperCards on the game thread. */
final class SideboardOptions {
    private final List<PaperCard> mainCards;
    private final List<PaperCard> sideCards;
    private final List<Option> main;
    private final List<Option> side;
    private final int mainSize;
    private final int sideSize;
    private final int minMain;
    private final int maxSide;

    private record Option(String name, int count) { }

    SideboardOptions(Deck deck, GameType gameType) {
        final var mainPool = deck.getMain();
        final var sidePool = deck.has(DeckSection.Sideboard) ? deck.get(DeckSection.Sideboard) : new CardPool();
        mainCards = mainPool.toFlatList().stream().distinct().sorted().toList();
        sideCards = sidePool.toFlatList().stream().distinct().sorted().toList();
        main = mainCards.stream().map(card -> new Option(card.getName(), mainPool.count(card))).toList();
        side = sideCards.stream().map(card -> new Option(card.getName(), sidePool.count(card))).toList();
        mainSize = mainPool.countAll();
        sideSize = sidePool.countAll();
        // Match the existing human sideboarding bounds, including casual decks smaller than the format minimum.
        minMain = Math.min(mainSize, gameType.getDeckFormat().getMainRange().getMinimum());
        final var sideRange = gameType.getDeckFormat().getSideRange();
        maxSide = sideRange == null ? mainSize + sideSize : sideRange.getMaximum();
    }

    boolean hasChoices() {
        return sideSize > 0 || mainSize > minMain;
    }

    void validate(SubmitSideboardPlan plan) {
        counts(plan.out(), main);
        counts(plan.in(), side);
        final int resultMain = mainSize - plan.out().size() + plan.in().size();
        final int resultSide = mainSize + sideSize - resultMain;
        if (resultMain < minMain || resultSide > maxSide) {
            throw new IllegalArgumentException("Resulting Main must contain at least " + minMain
                    + " cards and Sideboard at most " + maxSide + ".");
        }
    }

    private static int[] counts(List<Integer> indices, List<Option> options) {
        final int[] counts = new int[options.size()];
        for (int index : indices) {
            if (index < 0 || index >= options.size()) {
                throw new IllegalArgumentException("Choose only indices from the corresponding sideboarding menu.");
            }
            if (++counts[index] > options.get(index).count()) {
                throw new IllegalArgumentException("Not enough copies of " + options.get(index).name() + ".");
            }
        }
        return counts;
    }

    List<PaperCard> apply(SubmitSideboardPlan plan) {
        validate(plan);
        final int[] out = counts(plan.out(), main);
        final int[] in = counts(plan.in(), side);
        final List<PaperCard> result = new ArrayList<>();
        for (int i = 0; i < main.size(); i++) {
            for (int copy = out[i]; copy < main.get(i).count(); copy++) result.add(mainCards.get(i));
        }
        for (int i = 0; i < side.size(); i++) {
            for (int copy = 0; copy < in[i]; copy++) result.add(sideCards.get(i));
        }
        return result;
    }

    List<String> report(SubmitSideboardPlan plan) {
        final List<String> report = new ArrayList<>();
        describeMoves(report, "sideboarded out", counts(plan.out(), main), main);
        describeMoves(report, "sideboarded in", counts(plan.in(), side), side);
        return report.isEmpty() ? List.of("kept the same deck.") : List.copyOf(report);
    }

    private static void describeMoves(List<String> report, String action, int[] counts, List<Option> options) {
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > 0) report.add(action + " " + counts[i] + "x " + options.get(i).name() + ".");
        }
    }

    String prompt(String playerName, String requestId, String score) {
        final var text = new StringBuilder("You are playing as ").append(playerName)
                .append(".\nRequest ID: ").append(requestId)
                .append("\nProtocol: sideboard (session instructions).\nMatch score: ").append(score)
                .append("\nChoose your deck for the next game. Inspect get_previous_game_log to understand the matchup; ")
                .append("paginate as needed. Use get_card_details for card rules.\n")
                .append("Submit submit_sideboard_plan with out (Main indices) and in (Sideboard indices). ")
                .append("Repeat an index once per copy moved, up to its offered count. Empty arrays keep the deck.\n")
                .append("Current sizes: Main=").append(mainSize).append(", Sideboard=").append(sideSize)
                .append(". Resulting Main minimum=").append(minMain).append(", Sideboard maximum=").append(maxSide)
                .append(". Equal numbers in and out are not required.\n");
        menu(text, "Main", main);
        menu(text, "Sideboard", side);
        return text.toString();
    }

    private static void menu(StringBuilder text, String name, List<Option> options) {
        text.append(name).append(" options:\n");
        for (int i = 0; i < options.size(); i++) {
            text.append(i).append(": ").append(options.get(i).name()).append(" (copies: ")
                    .append(options.get(i).count()).append(")\n");
        }
    }
}
