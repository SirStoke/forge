package forge.ai.llm;

import forge.ai.llm.tools.RuleText;
import forge.ai.llm.tools.RulesIndex;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ComprehensiveRulesShould {
    private final ComprehensiveRules rules = ComprehensiveRules.bundled();

    @Test
    public void browseChaptersSectionsParagraphsAndSubrulesInBookOrder() {
        final var chapters = rules.index(new RulesIndex("", 0));
        Assert.assertEquals(chapters.entries().stream().map(ComprehensiveRules.Summary::id).toList(),
                List.of("1", "2", "3", "4", "5", "6", "7", "8", "9"));
        Assert.assertNull(chapters.nextOffset());
        Assert.assertTrue(chapters.edition().contains("November 6, 2026"));
        Assert.assertTrue(chapters.source().startsWith("https://media.wizards.com/"));
        Assert.assertEquals(rules.index(new RulesIndex("6", 0)).entries().get(1).id(), "601");
        Assert.assertEquals(rules.index(new RulesIndex("601", 0)).entries().get(1).id(), "601.2");
        Assert.assertEquals(rules.index(new RulesIndex("601.2", 0)).entries().get(0).id(), "601.2a");
        Assert.assertTrue(rules.index(new RulesIndex("702.2a", 0)).entries().isEmpty());
    }

    @Test
    public void returnOnlyTheExactParagraphWithItsExamplesAndContinuationText() {
        final var rule = rules.read(new RuleText("101.2"));
        Assert.assertEquals(rule.parent(), "101");
        Assert.assertTrue(rule.text().contains("takes precedence."));
        Assert.assertTrue(rule.text().contains("Example:"));
        Assert.assertFalse(rule.text().contains("Adding abilities")); // 101.2a is separate.
        Assert.assertEquals(rule.childCount(), 1);
        Assert.assertTrue(rules.read(new RuleText("103.1")).text().contains("The player chosen to take the first turn"));
        Assert.assertEquals(rules.read(new RuleText("702.2a")).text(), "Deathtouch is a static ability.");
        Assert.assertEquals(rules.read(new RuleText("905")).text(), "Conspiracy Draft");
    }

    @Test
    public void paginateLargeSectionsWithoutMissingDuplicatingOrExpandingEntries() {
        final var ids = new ArrayList<String>();
        Integer offset = 0;
        int total = 0;
        while (offset != null) {
            final var page = rules.index(new RulesIndex("702", offset));
            total = page.totalEntries();
            Assert.assertTrue(page.entries().size() <= RulesIndex.PAGE_SIZE);
            for (var entry : page.entries()) {
                Assert.assertTrue(entry.preview().length() <= 160);
                Assert.assertFalse(ids.contains(entry.id()));
                ids.add(entry.id());
            }
            offset = page.nextOffset();
        }
        Assert.assertTrue(total > RulesIndex.PAGE_SIZE);
        Assert.assertEquals(ids.size(), total);
        Assert.assertEquals(ids.get(0), "702.1");
        Assert.assertEquals(ids.get(9), "702.10");
        Assert.assertTrue(rules.index(new RulesIndex("702", total)).entries().isEmpty());
        Assert.expectThrows(IllegalArgumentException.class, () -> rules.index(new RulesIndex("702", totalCount() + 1)));
        Assert.expectThrows(IllegalArgumentException.class, () -> rules.index(new RulesIndex("702", Integer.MAX_VALUE)));
        Assert.expectThrows(IllegalArgumentException.class, () -> rules.index(new RulesIndex("999", 0)));
        Assert.expectThrows(IllegalArgumentException.class, () -> rules.read(new RuleText("702.9999")));
    }

    private int totalCount() {
        return rules.index(new RulesIndex("702", 0)).totalEntries();
    }

    @DataProvider
    public Object[][] invalidRuleArguments() {
        return new Object[][] {
                { null }, { Map.of() }, { Map.of("id", 601) }, { Map.of("id", "") },
                { Map.of("id", " ") }, { Map.of("id", "601.2a-601.2b") },
                { Map.of("id", "601", "extra", true) }, { Map.of("id", "../../rules") }
        };
    }

    @Test(dataProvider = "invalidRuleArguments")
    public void rejectInvalidRuleArguments(Map<String, Object> args) {
        Assert.expectThrows(IllegalArgumentException.class,
                () -> RuleText.compile(new CallToolRequest(RuleText.NAME, args)));
    }

    @DataProvider
    public Object[][] invalidIndexArguments() {
        return new Object[][] {
                { Map.of("parent", "") }, { Map.of("parent", 6) }, { Map.of("parent", "all") },
                { Map.of("offset", -1) }, { Map.of("offset", 0.5) }, { Map.of("offset", 1.0) },
                { Map.of("offset", "0") }, { Map.of("offset", Long.MAX_VALUE) }, { Map.of("extra", true) }
        };
    }

    @Test(dataProvider = "invalidIndexArguments")
    public void rejectInvalidIndexArguments(Map<String, Object> args) {
        Assert.expectThrows(IllegalArgumentException.class,
                () -> RulesIndex.compile(new CallToolRequest(RulesIndex.NAME, args)));
    }

    @Test
    public void normalizeRuleNumbersAndDefaultIndexArguments() {
        Assert.assertEquals(RuleText.compile(new CallToolRequest(RuleText.NAME, Map.of("id", " 601.2a. "))).id(), "601.2a");
        Assert.assertEquals(RulesIndex.compile(new CallToolRequest(RulesIndex.NAME, null)), new RulesIndex("", 0));
        Assert.assertEquals(RulesIndex.compile(new CallToolRequest(RulesIndex.NAME, Map.of())), new RulesIndex("", 0));
        Assert.assertEquals(RulesIndex.compile(new CallToolRequest(RulesIndex.NAME,
                Map.of("parent", " 702. ", "offset", 50L))), new RulesIndex("702", 50));
    }

    @Test
    public void serveRulebookToolsOverMcpWithoutAGameOrPendingDecision() throws Exception {
        try (var server = ForgeMcpServer.start()) {
            final var transport = HttpClientStreamableHttpTransport
                    .builder(URI.create(server.getUrl()).resolve("/").toString()).endpoint("/mcp").build();
            try (var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build()) {
                client.initialize();
                for (var tool : client.listTools().tools()) {
                    if (List.of(RulesIndex.NAME, RuleText.NAME).contains(tool.name())) {
                        Assert.assertTrue(tool.annotations().readOnlyHint());
                        Assert.assertFalse(tool.annotations().destructiveHint());
                        Assert.assertFalse(tool.annotations().openWorldHint());
                    }
                }
                final var index = client.callTool(new CallToolRequest(RulesIndex.NAME, Map.of("parent", "702.2")));
                Assert.assertFalse(index.isError());
                final var parsed = McpJsonDefaults.getMapper().readValue(
                        ((TextContent) index.content().get(0)).text(), ComprehensiveRules.Index.class);
                Assert.assertEquals(parsed.entries().get(0).id(), "702.2a");
                for (int i = 0; i < 2; i++) {
                    final var result = client.callTool(new CallToolRequest(RuleText.NAME, Map.of("id", "702.2a")));
                    Assert.assertFalse(result.isError());
                    final var paragraph = McpJsonDefaults.getMapper().readValue(
                            ((TextContent) result.content().get(0)).text(), ComprehensiveRules.Paragraph.class);
                    Assert.assertEquals(paragraph.text(), "Deathtouch is a static ability.");
                }
                Assert.assertTrue(client.callTool(new CallToolRequest(RuleText.NAME, Map.of("id", "999"))).isError());
                Assert.assertTrue(client.callTool(new CallToolRequest(RulesIndex.NAME, Map.of("parent", "999"))).isError());
                Assert.assertTrue(client.callTool(new CallToolRequest(RulesIndex.NAME, Map.of("offset", -1))).isError());
            }
        }
    }
}
