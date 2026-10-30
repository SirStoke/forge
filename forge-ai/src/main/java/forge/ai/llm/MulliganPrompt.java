package forge.ai.llm;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

final class MulliganPrompt {
    private static final Template TEMPLATE = loadTemplate();

    private MulliganPrompt() {
    }

    static String render(Player player, Player firstPlayer, int cardsToReturn) {
        return TEMPLATE.execute(Map.of(
                "playerName", player.getName(),
                "firstPlayerName", firstPlayer.getName(),
                "cardsToReturn", cardsToReturn,
                "cards", player.getCardsIn(ZoneType.Hand).stream().map(Card::getName).toList()));
    }

    private static Template loadTemplate() {
        try (var reader = new InputStreamReader(Objects.requireNonNull(
                MulliganPrompt.class.getResourceAsStream("mulligan.mustache")), StandardCharsets.UTF_8)) {
            return Mustache.compiler().escapeHTML(false).compile(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Could not load mulligan prompt", e);
        }
    }
}
