package forge.gamemodes.match;

import forge.ai.llm.AcpModels;
import org.testng.Assert;
import org.testng.annotations.Test;

public class AcpModelPreferencesShould {
    @Test
    public void rememberEachPlayersModelPairSeparatelyForEachHarness() {
        final var openCode = new AcpModels(" openrouter/z-ai/glm-5.3#low ", "openai/gpt-6.1-sol#high");
        final var claude = new AcpModels("claude-haiku-4-5", "claude-sonnet-4-5");
        String saved = AcpModelPreferences.write("", 1, "opencode", openCode);
        saved = AcpModelPreferences.write(saved, 1, "claude-acp", claude);
        saved = AcpModelPreferences.write(saved, 2, "opencode", new AcpModels("openai/other", ""));
        Assert.assertEquals(AcpModelPreferences.read(saved, 1, "opencode"), openCode);
        Assert.assertEquals(AcpModelPreferences.read(saved, 1, "claude-acp"), claude);
        Assert.assertEquals(AcpModelPreferences.read(saved, 2, "opencode").fastModel(), "openai/other");
        Assert.assertEquals(AcpModelPreferences.read(saved, 0, "opencode"), AcpModels.defaults());
        saved = AcpModelPreferences.write(saved, 1, "opencode", AcpModels.defaults());
        Assert.assertEquals(AcpModelPreferences.read(saved, 1, "opencode"), AcpModels.defaults());
        Assert.assertEquals(AcpModelPreferences.read(saved, 1, "claude-acp"), claude);
    }

    @Test
    public void handleLegacyMissingOrCorruptedPreferences() {
        for (String saved : new String[] {null, "", "broken", "[]", "{\"1\":false}"}) {
            Assert.assertEquals(AcpModelPreferences.read(saved, 1, "opencode"), AcpModels.defaults());
            final var models = new AcpModels("openai/fast", "openrouter/z-ai/planner");
            Assert.assertEquals(AcpModelPreferences.read(AcpModelPreferences.write(saved, 1, "opencode", models),
                    1, "opencode"), models);
        }
    }

    @Test
    public void acceptProviderQualifiedModelIdsAndRejectMalformedOrInjectedIds() {
        Assert.assertEquals(new AcpModels("openrouter/z-ai/glm-5.3", "openai/gpt-6.1-sol").validationError("opencode"), "");
        Assert.assertEquals(AcpModels.defaults().validationError("opencode"), "");
        Assert.assertEquals(new AcpModels("openrouter/z-ai/glm-5.3#low", "openai/gpt-6.1-sol#high").validationError("opencode"), "");
        for (String invalid : new String[] {"openai/model#", "openai/#high", "openai/model#low#high", "openai/model#high/extra"}) {
            Assert.assertFalse(new AcpModels(invalid, "").validationError("opencode").isEmpty(), invalid);
        }
        Assert.assertEquals(new AcpModels("native-model", "").validationError("claude-acp"), "");
        Assert.assertFalse(new AcpModels("glm-5.3", "").validationError("opencode").isEmpty());
        Assert.assertFalse(new AcpModels("openai/fast", "openai/").validationError("opencode").isEmpty());
        Assert.assertFalse(new AcpModels("openai/fast", "other\nmodel: injected").validationError("opencode").isEmpty());
    }
}
