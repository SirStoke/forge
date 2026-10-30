package forge.ai.llm;

import forge.ai.llm.tools.CardDetails;
import forge.card.CardDb;
import forge.card.CardRules;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class CardDetailsShould {
    private List<CardDb> databases;

    @BeforeClass
    public void loadCardDefinitions() throws IOException {
        databases = List.of(database("l/lightning_bolt.txt", "i/island.txt", "d/dryad_arbor.txt",
                "f/fire_ice.txt", "d/delver_of_secrets_insectile_aberration.txt"),
                database("a/akoum.txt"));
    }

    private CardDb database(String... files) throws IOException {
        final Map<String, CardRules> rules = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String file : files) {
            final CardRules card = new CardRules.Reader()
                    .readCard(Files.readAllLines(Path.of("res/cardsfolder", file)));
            rules.put(card.getName(), card);
        }
        // Rules lookup does not require printing/edition data.
        return new CardDb(rules, null, Set.of());
    }

    private CardCatalog.CardInfo lookup(String name) {
        final CardDetails request = CardDetails.compile(new CallToolRequest(CardDetails.NAME, Map.of("name", name)));
        return CardCatalog.lookup(request, databases);
    }

    @Test
    public void returnPrintedCostTypeColorsAndEffectsByName() {
        final var card = lookup("  lightning bolt  ");
        Assert.assertEquals(card.name(), "Lightning Bolt");
        Assert.assertEquals(card.faces().size(), 1);
        final var face = card.faces().get(0);
        Assert.assertEquals(face.manaCost(), "{R}");
        Assert.assertEquals(face.type(), "Instant");
        Assert.assertEquals(face.colors(), List.of("red"));
        Assert.assertEquals(face.effects(), "Lightning Bolt deals 3 damage to any target.");
    }

    @Test
    public void distinguishPrintedColorsFromManaCostAndColorIdentity() {
        final var island = lookup("Island").faces().get(0);
        Assert.assertEquals(island.manaCost(), "no cost");
        Assert.assertEquals(island.colors(), List.of("colorless"));
        final var dryad = lookup("Dryad Arbor").faces().get(0);
        Assert.assertEquals(dryad.manaCost(), "no cost");
        Assert.assertEquals(dryad.colors(), List.of("green"));
        Assert.assertEquals(dryad.power(), "1");
        Assert.assertEquals(dryad.toughness(), "1");
    }

    @Test
    public void includeEveryFaceWhenLookingUpSplitOrDoubleFacedCards() {
        final var split = lookup("Ice");
        Assert.assertEquals(split.name(), "Fire // Ice");
        Assert.assertEquals(split.faces().size(), 2);
        Assert.assertEquals(split.faces().get(0).manaCost(), "{1}{R}");
        Assert.assertEquals(split.faces().get(1).manaCost(), "{1}{U}");
        Assert.assertEquals(split.faces().get(1).effects(), "Tap target permanent.\nDraw a card.");

        final var delver = lookup("Insectile Aberration");
        Assert.assertEquals(delver.name(), "Delver of Secrets");
        Assert.assertEquals(delver.faces().size(), 2);
        Assert.assertEquals(delver.faces().get(1).effects(), "Flying");
        Assert.assertEquals(delver.faces().get(1).colors(), List.of("blue"));
        Assert.assertEquals(delver.faces().get(1).power(), "3");
    }

    @Test
    public void searchVariantCardsAndReportUnknownNames() {
        Assert.assertTrue(lookup("Akoum").faces().get(0).type().startsWith("Plane"));
        final var error = Assert.expectThrows(IllegalArgumentException.class,
                () -> lookup("A card that does not exist"));
        Assert.assertTrue(error.getMessage().contains("Card not found"));
    }

    @DataProvider
    public Object[][] invalidArguments() {
        return new Object[][] {
                { null }, { Map.of() }, { Map.of("name", 42) }, { Map.of("name", "") },
                { Map.of("name", " \t ") }, { Map.of("name", "Island", "extra", true) }
        };
    }

    @Test(dataProvider = "invalidArguments")
    public void rejectInvalidArguments(Map<String, Object> arguments) {
        Assert.expectThrows(IllegalArgumentException.class,
                () -> CardDetails.compile(new CallToolRequest(CardDetails.NAME, arguments)));
    }

    @Test
    public void serveRepeatedCatalogLookupsThroughTheRepositoryWithoutAGame() throws Exception {
        final var repository = new ToolRepository();
        repository.register(CardDetails::schema, CardDetails::compile,
                request -> CardCatalog.lookup(request, databases));
        final var handler = repository.specifications().get(0).callHandler();
        for (String name : List.of("Lightning Bolt", "Dryad Arbor", "Lightning Bolt")) {
            final var result = handler.apply(null, new CallToolRequest(CardDetails.NAME, Map.of("name", name)));
            Assert.assertFalse(result.isError());
            final String json = ((TextContent) result.content().get(0)).text();
            Assert.assertEquals(McpJsonDefaults.getMapper().readValue(json, CardCatalog.CardInfo.class), lookup(name));
        }
        Assert.assertTrue(handler.apply(null, new CallToolRequest(CardDetails.NAME,
                Map.of("name", "A card that does not exist"))).isError());
        Assert.assertTrue(handler.apply(null, new CallToolRequest(CardDetails.NAME,
                Map.of("name", " "))).isError());
    }
}
