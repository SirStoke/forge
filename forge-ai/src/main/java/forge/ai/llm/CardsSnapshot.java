package forge.ai.llm;

import forge.card.CardStateName;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.List;

/** Immutable card lists captured on the game thread from this agent's perspective. */
public record CardsSnapshot(List<CardInfo> cards) {
    public CardsSnapshot {
        cards = List.copyOf(cards);
    }

    static CardsSnapshot hand(Player viewer) {
        return capture(viewer.getCardsIn(ZoneType.Hand), viewer);
    }

    static CardsSnapshot battlefield(Player viewer) {
        return capture(viewer.getGame().getCardsIn(ZoneType.Battlefield), viewer);
    }

    private static CardsSnapshot capture(Iterable<Card> cards, Player viewer) {
        final List<CardInfo> result = new ArrayList<>();
        for (Card card : cards) {
            final boolean visible = card.getView().canBeShownTo(viewer.getView())
                    && card.getView().canFaceDownBeShownTo(viewer.getView());
            final String name = visible
                    ? (card.isFaceDown() ? card.getOriginalState(CardStateName.Original).getName() : card.getName())
                    : (card.isFaceDown() ? "Face-down card" : "Hidden card");
            result.add(new CardInfo(card.getId(), name, PlayerInfo.from(card.getOwner()),
                    PlayerInfo.from(card.getController()), card.isTapped(), card.isFaceDown()));
        }
        return new CardsSnapshot(result);
    }

    public record CardInfo(int id, String name, PlayerInfo owner, PlayerInfo controller,
                           boolean tapped, boolean faceDown) {
    }

    public record PlayerInfo(int id, String name) {
        private static PlayerInfo from(Player player) {
            return player == null ? null : new PlayerInfo(player.getId(), player.getName());
        }
    }
}
