package forge.ai.llm;

import forge.game.player.Player;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.mockito.Mockito.mock;

public class LlmEvalConfigurationShould {
    @Test
    public void preserveCodexDefaultsWhenNoEvalOverridesAreProvided() {
        final var configuration = LlmEvalConfiguration.fromProperties(new Properties());
        Assert.assertEquals(configuration.acpAgent(), AcpAgentRegistry.CODEX);
        Assert.assertEquals(configuration.models(), AcpModels.defaults());
    }

    @Test
    public void acceptEachSupportedHarnessAndItsNativeModelIds() {
        for (String id : List.of("opencode", "codex-acp", "claude-acp", "github-copilot")) {
            final var properties = new Properties();
            properties.setProperty("forge.llm.eval.acp", id);
            final String fast = id.equals("opencode") ? "openrouter/z-ai/glm-5.3#low" : "fast-native-model";
            final String planner = id.equals("opencode") ? "openai/gpt-6.1-sol#high" : "planning-native-model";
            properties.setProperty("forge.llm.eval.fastModel", " " + fast + " ");
            properties.setProperty("forge.llm.eval.planningModel", planner);
            final var configuration = LlmEvalConfiguration.fromProperties(properties);
            Assert.assertEquals(configuration.acpAgent(), id);
            Assert.assertEquals(configuration.models(), new AcpModels(fast, planner));
        }
    }

    @Test
    public void keepMissingModelOverridesEmptyForNativeDefaultsAndAllowIndependentOverrides() {
        final var properties = new Properties();
        properties.setProperty("forge.llm.eval.acp", " opencode ");
        properties.setProperty("forge.llm.eval.planningModel", "openai/gpt-6.1-sol#high");
        final var configuration = LlmEvalConfiguration.fromProperties(properties);
        Assert.assertEquals(configuration.acpAgent(), "opencode");
        Assert.assertEquals(configuration.models(), new AcpModels("", "openai/gpt-6.1-sol#high"));
        properties.remove("forge.llm.eval.planningModel");
        properties.setProperty("forge.llm.eval.fastModel", "openrouter/z-ai/glm-5.3");
        Assert.assertEquals(LlmEvalConfiguration.fromProperties(properties).models(), new AcpModels("openrouter/z-ai/glm-5.3", ""));
    }

    @Test
    public void rejectUnknownAgentsAndMalformedModelsBeforeStartingAnAgent() {
        final var unknown = new Properties();
        unknown.setProperty("forge.llm.eval.acp", "not-an-acp-agent");
        Assert.assertTrue(Assert.expectThrows(IllegalArgumentException.class,
                () -> LlmEvalConfiguration.fromProperties(unknown)).getMessage().contains("forge.llm.eval.acp"));
        for (String property : List.of("forge.llm.eval.fastModel", "forge.llm.eval.planningModel")) {
            final var invalid = new Properties();
            invalid.setProperty("forge.llm.eval.acp", "opencode");
            invalid.setProperty(property, "missing-provider#high");
            Assert.expectThrows(IllegalArgumentException.class, () -> LlmEvalConfiguration.fromProperties(invalid));
        }
    }

    @Test
    public void launchTheSelectedHarnessAndModelPair() {
        final var previous = new java.util.LinkedHashMap<String, String>();
        for (String property : List.of("forge.llm.eval.acp", "forge.llm.eval.fastModel", "forge.llm.eval.planningModel")) {
            previous.put(property, System.getProperty(property));
        }
        try {
            System.setProperty("forge.llm.eval.acp", "opencode");
            System.setProperty("forge.llm.eval.fastModel", "openrouter/z-ai/glm-5.3#low");
            System.setProperty("forge.llm.eval.planningModel", "openai/gpt-6.1-sol#high");
            final var configuration = LlmEvalConfiguration.fromSystemProperties();
            final var player = mock(Player.class);
            final var calls = new java.util.concurrent.atomic.AtomicInteger();
            final var factory = configuration.agentFactory(player, (directory, participant, agentId, models) -> {
                Assert.assertEquals(directory, Path.of("."));
                Assert.assertSame(participant, player);
                Assert.assertEquals(agentId, "opencode");
                Assert.assertEquals(models, new AcpModels("openrouter/z-ai/glm-5.3#low", "openai/gpt-6.1-sol#high"));
                calls.incrementAndGet();
                return null;
            });
            Assert.assertEquals(calls.get(), 0, "Creating the controller's factory must not launch an agent");
            factory.get();
            Assert.assertEquals(calls.get(), 1);
        } finally {
            previous.forEach((property, value) -> {
                if (value == null) System.clearProperty(property);
                else System.setProperty(property, value);
            });
        }
    }
}
