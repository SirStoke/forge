package forge.ai.llm;

import forge.game.player.Player;
import org.tinylog.Logger;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Supplier;

/** Shared launch settings for opt-in live evals; never read the desktop lobby's preferences. */
record LlmEvalConfiguration(String acpAgent, AcpModels models) {
    static final String ACP_PROPERTY = "forge.llm.eval.acp";
    static final String FAST_MODEL_PROPERTY = "forge.llm.eval.fastModel";
    static final String PLANNING_MODEL_PROPERTY = "forge.llm.eval.planningModel";

    LlmEvalConfiguration {
        acpAgent = acpAgent == null || acpAgent.isBlank() ? AcpAgentRegistry.CODEX : acpAgent.trim();
        final String selectedAgent = acpAgent;
        if (AcpAgentRegistry.entries().stream().noneMatch(entry -> entry.id().equals(selectedAgent))) {
            throw new IllegalArgumentException(ACP_PROPERTY + " must be one of "
                    + AcpAgentRegistry.entries().stream().map(AcpAgentRegistry.Entry::id).toList());
        }
        Objects.requireNonNull(models, "models");
        final String invalid = models.validationError(acpAgent);
        if (!invalid.isEmpty()) throw new IllegalArgumentException("Invalid live eval model selection: " + invalid);
    }

    static LlmEvalConfiguration fromSystemProperties() {
        return fromProperties(System.getProperties());
    }

    static LlmEvalConfiguration fromProperties(Properties properties) {
        return new LlmEvalConfiguration(properties.getProperty(ACP_PROPERTY), new AcpModels(
                properties.getProperty(FAST_MODEL_PROPERTY), properties.getProperty(PLANNING_MODEL_PROPERTY)));
    }

    Supplier<AcpAgent> agentFactory(Player player) {
        return agentFactory(player, AcpAgent::start);
    }

    @FunctionalInterface
    interface Starter {
        AcpAgent start(Path directory, Player player, String agentId, AcpModels models);
    }

    Supplier<AcpAgent> agentFactory(Player player, Starter starter) {
        return () -> {
            final var resolved = models.resolve(acpAgent);
            Logger.info("Live eval configuration: ACP={}, fast model={}, planning model={}", acpAgent,
                    resolved.fastModel().isEmpty() ? "agent default" : resolved.fastModel(),
                    resolved.planningModel().isEmpty() ? "inherit fast model" : resolved.planningModel());
            return starter.start(Path.of("."), player, acpAgent, models);
        };
    }
}
