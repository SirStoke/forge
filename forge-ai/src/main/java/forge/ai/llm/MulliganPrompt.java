package forge.ai.llm;

import com.samskivert.mustache.Template;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import java.util.Map;

final class MulliganPrompt {
    private static final Template TEMPLATE = PromptTemplates.load("mulligan.mustache");

    private MulliganPrompt() {
    }

    static String render(Player player, Player firstPlayer, int cardsToReturn, String requestId) {
        return TEMPLATE.execute(Map.of(
                "requestId", requestId,
                "playerName", player.getName(),
                "firstPlayerName", firstPlayer.getName(),
                "cardsToReturn", cardsToReturn,
                "cards", player.getCardsIn(ZoneType.Hand).stream().map(Card::getName).toList()));
    }

}
