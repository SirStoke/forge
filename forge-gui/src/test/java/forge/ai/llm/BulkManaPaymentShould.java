package forge.ai.llm;

import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import forge.ai.llm.tools.CancelAction;
import forge.ai.llm.tools.PayMana;
import forge.ai.llm.tools.SubmitChoice;
import forge.card.MagicColor;
import forge.game.card.Card;
import forge.game.zone.ZoneType;
import forge.util.Lang;
import forge.util.Localizer;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** Real MCP and engine payment: counts prompts as well as checking the resulting resources. */
public class BulkManaPaymentShould {
    @BeforeClass
    public void initializeLanguage() {
        Localizer.getInstance().initialize("en-US", "res/languages/");
        Lang.createInstance("en-US");
    }

    private Card spell(GameplayChoicesShould.Session s, String cost) {
        return s.card(s.player, ZoneType.Hand, "Payment spell", "ManaCost:" + cost, "Types:Instant", "Oracle:",
                "A:SP$ GainLife | Defined$ You | LifeAmount$ 1 | SpellDescription$ Gain 1 life.");
    }

    private Card source(GameplayChoicesShould.Session s, String name, String produced, String... extra) {
        String[] script = new String[extra.length + 4];
        script[0] = "ManaCost:0"; script[1] = "Types:Artifact"; script[2] = "Oracle:";
        script[3] = "A:AB$ Mana | Cost$ T | Produced$ " + produced + " | SpellDescription$ Add mana.";
        System.arraycopy(extra, 0, script, 4, extra.length);
        return s.card(s.player, ZoneType.Battlefield, name, script);
    }

    private void answer(GameplayChoicesShould.Session s, Function<String, CallToolRequest> choose) {
        doAnswer(invocation -> {
            String prompt = ((PromptRequest) invocation.getArgument(0)).text();
            s.prompts.add(prompt);
            Assert.assertFalse(s.mcp.callTool(choose.apply(prompt)).isError(), prompt);
            return null;
        }).when(s.client).prompt(any());
    }

    private CallToolRequest plan(GameplayChoicesShould.Session s, String prompt, List<Map<String, Object>> steps) {
        return new CallToolRequest(PayMana.NAME, Map.of("requestId", s.id(prompt), "steps", steps));
    }

    private Map<String, Object> activation(GameplayChoicesShould.Session s, String prompt, String source) {
        return Map.of("option", s.option(prompt, "Activate mana ability: " + source));
    }

    @Test
    public void payForSeveralLandsInOneResponseAndLeaveUnneededSourcesUntapped() {
        try (var s = new GameplayChoicesShould.Session()) {
            Card r1 = s.mountain("First Mountain"), r2 = s.mountain("Second Mountain");
            Card u1 = source(s, "First blue", "U"), u2 = source(s, "Second blue", "U");
            Card extra = s.mountain("Extra Mountain");
            Card spell = spell(s, "2 R U");
            answer(s, prompt -> plan(s, prompt, List.of(activation(s, prompt, "First Mountain"),
                    activation(s, prompt, "First blue"), activation(s, prompt, "Second Mountain"),
                    activation(s, prompt, "Second blue"), activation(s, prompt, "Extra Mountain"))));
            Assert.assertTrue(s.controller.playChosenSpellAbility(spell.getSpellAbilities().get(0)));
            Assert.assertEquals(s.prompts.size(), 1, "One batch must replace all source/spending turns");
            Assert.assertTrue(r1.isTapped() && r2.isTapped() && u1.isTapped() && u2.isTapped());
            Assert.assertFalse(extra.isTapped());
            Assert.assertEquals(s.game.getStack().peek().getSpellAbility().getPayingMana().size(), 4);
            Assert.assertEquals(s.player.getManaPool().totalMana(), 0);
        }
    }

    @Test
    public void spendOnlyNewManaAndKeepUnselectedEquivalentFloatingMana() {
        try (var s = new GameplayChoicesShould.Session()) {
            Card old = s.mountain("Existing source");
            Assert.assertTrue(s.controller.playChosenSpellAbility(old.getManaAbilities().get(0)));
            var original = s.player.getManaPool().iterator().next();
            Card selected = source(s, "Three mana", "R R R");
            Card spell = spell(s, "2");
            answer(s, prompt -> plan(s, prompt, List.of(activation(s, prompt, "Three mana"))));
            Assert.assertTrue(s.controller.playChosenSpellAbility(spell.getSpellAbilities().get(0)));
            Assert.assertEquals(s.prompts.size(), 1);
            Assert.assertTrue(selected.isTapped());
            Assert.assertEquals(s.player.getManaPool().totalMana(), 2, "Keep both original mana and one surplus unit");
            Assert.assertTrue(java.util.stream.StreamSupport.stream(s.player.getManaPool().spliterator(), false)
                    .anyMatch(mana -> mana == original), "Equivalent mana from an unselected source must remain");
            Assert.assertTrue(s.game.getStack().peek().getSpellAbility().getPayingMana().stream()
                    .allMatch(mana -> mana.getSourceCard().getId() == selected.getId()));
        }
    }

    @Test
    public void chooseSourceColorsInThePlanWithoutAnotherPrompt() {
        try (var s = new GameplayChoicesShould.Session()) {
            Card first = source(s, "First dual", "Combo R U");
            Card second = source(s, "Second dual", "Any");
            Card spell = spell(s, "R U");
            answer(s, prompt -> plan(s, prompt, List.of(
                    Map.of("option", s.option(prompt, "Activate mana ability: First dual"), "colors", List.of("R")),
                    Map.of("option", s.option(prompt, "Activate mana ability: Second dual"), "colors", List.of("U")))));
            Assert.assertTrue(s.controller.playChosenSpellAbility(spell.getSpellAbilities().get(0)));
            Assert.assertEquals(s.prompts.size(), 1);
            Assert.assertTrue(first.isTapped() && second.isTapped());
            Assert.assertEquals(s.game.getStack().peek().getSpellAbility().getPayingMana().stream()
                    .map(mana -> mana.getColor()).toList(), List.of(MagicColor.RED, MagicColor.BLUE));
        }
    }

    @Test
    public void chooseAMultiManaColorComboInThePlan() {
        try (var s = new GameplayChoicesShould.Session()) {
            s.card(s.player, ZoneType.Battlefield, "Combo source", "ManaCost:0", "Types:Artifact", "Oracle:",
                    "A:AB$ Mana | Cost$ T | Produced$ Combo R U | Amount$ 2 | SpellDescription$ Add two mana.");
            Card spell = spell(s, "R U");
            answer(s, prompt -> plan(s, prompt, List.of(Map.of("option", s.option(prompt, "Activate mana ability:"),
                    "colors", List.of("R", "U")))));
            Assert.assertTrue(s.controller.playChosenSpellAbility(spell.getSpellAbilities().get(0)));
            Assert.assertEquals(s.prompts.size(), 1);
            Assert.assertEquals(s.game.getStack().peek().getSpellAbility().getPayingMana().size(), 2);
        }
    }

    @Test
    public void promptForAnIllegalPlannedColorAndResumeTheBatch() {
        try (var s = new GameplayChoicesShould.Session()) {
            source(s, "Dual", "Combo R U");
            Card spell = spell(s, "U");
            answer(s, prompt -> prompt.contains("Choose color")
                    ? new CallToolRequest(SubmitChoice.NAME, s.choices(prompt, ": 2"))
                    : plan(s, prompt, List.of(Map.of("option", s.option(prompt, "Activate mana ability:"), "colors", List.of("G")))));
            Assert.assertTrue(s.controller.playChosenSpellAbility(spell.getSpellAbilities().get(0)));
            Assert.assertEquals(s.prompts.size(), 2, "Invalid preferences must use the real production menu");
            Assert.assertEquals(s.game.getStack().peek().getSpellAbility().getPayingMana().get(0).getColor(), MagicColor.BLUE);
        }
    }

    @Test
    public void paySeveralPhyrexianSymbolsWithOneBatch() {
        try (var s = new GameplayChoicesShould.Session()) {
            Card spell = spell(s, "R/P R/P");
            answer(s, prompt -> {
                int index = s.option(prompt, "Pay 2 life");
                return plan(s, prompt, List.of(Map.of("option", index), Map.of("option", index)));
            });
            Assert.assertTrue(s.controller.playChosenSpellAbility(spell.getSpellAbilities().get(0)));
            Assert.assertEquals(s.prompts.size(), 1);
            Assert.assertEquals(s.player.getLife(), 16);
        }
    }

    @Test
    public void recheckRepeatedSourcesAndAllowCancellationWithManaRefund() {
        try (var s = new GameplayChoicesShould.Session()) {
            Card land = s.mountain("Only source");
            Card spell = spell(s, "2 R");
            AtomicInteger prompts = new AtomicInteger();
            answer(s, prompt -> {
                if (prompts.getAndIncrement() == 0) {
                    var first = activation(s, prompt, "Only source");
                    return plan(s, prompt, List.of(first, first));
                }
                Assert.assertTrue(prompt.contains("Payment plan paused"));
                return new CallToolRequest(CancelAction.NAME, Map.of("requestId", s.id(prompt)));
            });
            Assert.assertFalse(s.controller.playChosenSpellAbility(spell.getSpellAbilities().get(0)));
            Assert.assertEquals(prompts.get(), 2);
            Assert.assertTrue(spell.isInZone(ZoneType.Hand));
            Assert.assertFalse(land.isTapped());
            Assert.assertEquals(s.player.getManaPool().totalMana(), 0);
            Assert.assertTrue(s.game.getStack().isEmpty());
            s.prompts.clear();
            answer(s, prompt -> new CallToolRequest("pass_priority", Map.of("requestId", s.id(prompt))));
            Assert.assertNull(s.controller.chooseSpellAbilityToPlay());
            Assert.assertEquals(s.prompts.size(), 1, "Explicit cancellation must return to the LLM without automatic passing");
        }
    }

    @Test
    public void preserveAdditionalSourceCostDecisionsInsideTheBatch() {
        try (var s = new GameplayChoicesShould.Session()) {
            s.card(s.player, ZoneType.Battlefield, "Life source", "ManaCost:0", "Types:Artifact", "Oracle:",
                    "A:AB$ Mana | Cost$ PayLife<2> | Produced$ R | SpellDescription$ Add red.");
            Card spell = spell(s, "R");
            answer(s, prompt -> prompt.contains("Pay this cost?")
                    ? new CallToolRequest(SubmitChoice.NAME, s.choices(prompt, "Yes"))
                    : plan(s, prompt, List.of(activation(s, prompt, "Life source"))));
            Assert.assertTrue(s.controller.playChosenSpellAbility(spell.getSpellAbilities().get(0)));
            Assert.assertEquals(s.prompts.size(), 2);
            Assert.assertEquals(s.player.getLife(), 18);
        }
    }

    @Test
    public void rejectStaleAndInvalidPlansWithoutConsumingThePendingChoice() {
        try (var s = new GameplayChoicesShould.Session()) {
            Card land = s.mountain("Source");
            Card spell = spell(s, "R");
            doAnswer(invocation -> {
                String prompt = ((PromptRequest) invocation.getArgument(0)).text();
                s.prompts.add(prompt);
                Assert.assertTrue(s.mcp.callTool(new CallToolRequest(PayMana.NAME,
                        Map.of("requestId", "old", "steps", List.of(Map.of("option", 0))))).isError());
                Assert.assertTrue(s.mcp.callTool(plan(s, prompt, List.of(Map.of("option", 10000)))).isError());
                var accepted = plan(s, prompt, List.of(activation(s, prompt, "Source")));
                Assert.assertFalse(s.mcp.callTool(accepted).isError());
                Assert.assertFalse(land.isTapped());
                Assert.assertTrue(s.mcp.callTool(accepted).isError());
                Assert.assertTrue(s.mcp.callTool(new CallToolRequest(CancelAction.NAME, Map.of("requestId", s.id(prompt)))).isError());
                return null;
            }).when(s.client).prompt(any());
            Assert.assertTrue(s.controller.playChosenSpellAbility(spell.getSpellAbilities().get(0)));
            Assert.assertEquals(s.prompts.size(), 1);
            s.prompts.clear();
            answer(s, prompt -> {
                Assert.assertTrue(s.mcp.callTool(plan(s, prompt, List.of(Map.of("option", 0)))).isError(), "Not a mana prompt");
                return new CallToolRequest(SubmitChoice.NAME, Map.of("requestId", s.id(prompt), "value", 1));
            });
            Assert.assertEquals(s.controller.chooseNumber(null, "Effect number", 0, 2), 1);
        }
    }

    @Test
    public void validatePaymentPlanShapesAndBounds() {
        var valid = PayMana.compile(new CallToolRequest(PayMana.NAME, Map.of("requestId", "r", "steps",
                List.of(Map.of("option", 2L, "colors", List.of("R", "U"))))));
        Assert.assertEquals(valid.steps().get(0), new PayMana.Step(2, List.of("R", "U")));
        List<Map<String, Object>> invalid = new ArrayList<>(List.of(Map.of(), Map.of("requestId", "r"),
                Map.of("requestId", " ", "steps", List.of(Map.of("option", 0))),
                Map.of("requestId", "r", "steps", List.of()),
                Map.of("requestId", "r", "steps", List.of(Map.of("option", -1))),
                Map.of("requestId", "r", "steps", List.of(Map.of("option", 1.5))),
                Map.of("requestId", "r", "steps", List.of(Map.of("option", Long.MAX_VALUE))),
                Map.of("requestId", "r", "steps", List.of(Map.of("option", 0, "colors", List.of("purple")))),
                Map.of("requestId", "r", "steps", List.of(Map.of("option", 0, "extra", true))),
                Map.of("requestId", "r", "steps", java.util.Collections.nCopies(257, Map.of("option", 0)))));
        for (var args : invalid) Assert.expectThrows(IllegalArgumentException.class,
                () -> PayMana.compile(new CallToolRequest(PayMana.NAME, args)));
    }
}
