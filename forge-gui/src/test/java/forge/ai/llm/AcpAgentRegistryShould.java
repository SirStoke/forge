package forge.ai.llm;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

public class AcpAgentRegistryShould {
    private String catalog() throws Exception {
        try (var input = AcpAgentRegistry.class.getResourceAsStream("acp-registry.json")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private AcpAgentRegistry.Entry entry(List<AcpAgentRegistry.Entry> entries, String id) {
        return entries.stream().filter(entry -> entry.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    public void resolveRegistryRecipesWithoutStartingAgents() throws Exception {
        final Function<String, Path> executables = name -> List.of("pnpm", "opencode").contains(name)
                ? Path.of("/tools", name) : null;
        final var entries = AcpAgentRegistry.parse(catalog(), executables, "darwin-aarch64");
        Assert.assertEquals(entries.stream().map(AcpAgentRegistry.Entry::id).toList(),
                List.of("opencode", "codex-acp", "claude-acp", "github-copilot"));
        Assert.assertEquals(entry(entries, "opencode").command(), List.of("/tools/opencode", "acp"));
        Assert.assertTrue(entry(entries, "opencode").available());
        final var claude = entry(entries, "claude-acp");
        Assert.assertEquals(claude.command().subList(0, 2), List.of("/tools/pnpm", "dlx"));
        Assert.assertTrue(claude.command().get(2).startsWith("@agentclientprotocol/claude-agent-acp@"));
        Assert.assertEquals(claude.command().get(3), "--hide-claude-auth");
        Assert.assertEquals(entry(entries, "github-copilot").command().get(3), "--acp");
    }

    @Test
    public void useNpmWhenPnpmAndNpxAreAbsentAndExplainMissingTools() throws Exception {
        final var entries = AcpAgentRegistry.parse(catalog(), name -> name.equals("npm")
                ? Path.of("/tools/npm") : null, "linux-x86_64");
        Assert.assertEquals(entry(entries, "codex-acp").command().subList(0, 4),
                List.of("/tools/npm", "exec", "--yes", "--"));
        Assert.assertTrue(entry(entries, "codex-acp").available());
        Assert.assertFalse(entry(entries, "opencode").available());
        Assert.assertTrue(entry(entries, "opencode").unavailableReason().contains("opencode"));
        final var missing = AcpAgentRegistry.parse(catalog(), name -> null, "linux-x86_64");
        Assert.assertTrue(missing.stream().noneMatch(AcpAgentRegistry.Entry::available));
        Assert.assertTrue(entry(missing, "codex-acp").unavailableReason().contains("PATH"));
    }

    @Test
    public void retainOlderInstalledBinariesAndFindWindowsCommandShims() throws Exception {
        final Path directory = Files.createTempDirectory("forge-acp-path-");
        try {
            final Path shim = Files.createFile(directory.resolve("npm.CMD"));
            Assert.assertEquals(AcpAgentRegistry.findExecutable("npm", directory.toString(), ".EXE;.CMD", true), shim);
            Assert.assertNull(AcpAgentRegistry.findExecutable("pnpm", directory.toString(), ".EXE;.CMD", true));
        } finally {
            Files.deleteIfExists(directory.resolve("npm.CMD"));
            Files.deleteIfExists(directory);
        }
        final var entries = AcpAgentRegistry.parse(catalog(), name -> name.equals("opencode")
                ? Path.of("/tools/opencode") : null, "linux-aarch64");
        Assert.assertTrue(entry(entries, "opencode").available(), "Registry version must not filter an installed CLI");
    }

    @Test
    public void rejectMalformedCatalogsAndMarkRemovedAgentsUnavailable() throws Exception {
        Assert.expectThrows(java.io.IOException.class, () -> AcpAgentRegistry.parse("{}", name -> null, "linux-x86_64"));
        final var entries = AcpAgentRegistry.parse("""
                {"agents":[{"id":"codex-acp","name":"Codex","version":"1.0.0",
                "distribution":{"npx":{"package":"codex-acp@1.0.0"}}}]}
                """, name -> Path.of("/tools", name), "linux-x86_64");
        Assert.assertFalse(entry(entries, "claude-acp").available());
        Assert.assertTrue(entry(entries, "claude-acp").unavailableReason().contains("Not listed"));
    }
}
