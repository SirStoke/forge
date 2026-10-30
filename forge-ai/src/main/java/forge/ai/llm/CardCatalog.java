package forge.ai.llm;

import forge.StaticData;
import forge.ai.llm.tools.CardDetails;
import forge.card.CardDb;
import forge.card.ICardFace;
import forge.card.MagicColor;

import java.util.List;

/** Reads printed card definitions rather than mutable game objects. */
final class CardCatalog {
    private CardCatalog() {
    }

    static CardInfo lookup(CardDetails request) {
        final StaticData data = StaticData.instance();
        if (data == null) {
            throw new IllegalStateException("Card database is not initialized");
        }
        return lookup(request, data.getAvailableDatabases().values());
    }

    static CardInfo lookup(CardDetails request, Iterable<CardDb> databases) {
        for (CardDb database : databases) {
            final var rules = database.getRules(request.name(), true);
            if (rules != null) {
                return new CardInfo(rules.getName(), rules.getAllFaces().stream().map(Face::from).toList());
            }
        }
        throw new IllegalArgumentException("Card not found: " + request.name());
    }

    public record CardInfo(String name, List<Face> faces) {
    }

    public record Face(String name, String manaCost, String type, List<String> colors, String effects,
                       String power, String toughness, String loyalty, String defense) {
        private static Face from(ICardFace face) {
            return new Face(face.getName(), face.getManaCost().toString(), face.getType().toString(),
                    face.getColor().stream().map(MagicColor.Color::getName).toList(),
                    face.getOracleText().replace("\\n", "\n"),
                    face.getPower(), face.getToughness(), face.getInitialLoyalty(), face.getDefense());
        }
    }
}
