package forge.ai.llm;

import com.google.common.eventbus.Subscribe;
import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.event.GameEventGameFinished;
import forge.game.event.GameEventGameStarted;
import forge.game.player.Player;
import forge.game.player.PlaySpellAbility;
import forge.game.spellability.SpellAbility;
import org.tinylog.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Harness-controlled gameplay with validated choices and local AI fallback.
 */
public class PlayerControllerLlm extends LlmDecisionController {
    private final Supplier<CodexAcpAgent> agentFactory;
    private CodexAcpAgent agent;
    private boolean initializationAttempted;
    private boolean finished;
    private boolean passAfterFailedPlay;

    @Override
    protected CodexAcpAgent decisionAgent() {
        return agent;
    }

    public PlayerControllerLlm(Game game, Player player, LobbyPlayer lobbyPlayer) {
        this(game, player, lobbyPlayer, () -> CodexAcpAgent.start(Path.of("."), player));
    }

    PlayerControllerLlm(Game game, Player player, LobbyPlayer lobbyPlayer, Supplier<CodexAcpAgent> agentFactory) {
        super(game, player, lobbyPlayer);
        this.agentFactory = agentFactory;
        game.subscribeToEvents(this);
    }

    @Subscribe
    public void onGameStarted(GameEventGameStarted event) {
        // Copies may advance phases for simulations; they must not start agents.
        if (getGame().isSimulationCopy() || initializationAttempted || finished) {
            return;
        }
        initializationAttempted = true;
        try {
            agent = agentFactory.get();
            Logger.info("Initialized Codex ACP for {} with {}", player.getName(), CodexAcpAgent.MODEL);
        } catch (RuntimeException e) {
            Logger.error(e, "Could not initialize Codex ACP for {}", player.getName());
        }
    }

    @Subscribe
    public void onGameFinished(GameEventGameFinished event) {
        finished = true;
        if (agent != null) {
            agent.close();
            agent = null;
        }
    }

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        if (agent != null && !getGame().isSimulationCopy()) {
            try {
                final var decision = new CompletableFuture<Boolean>();
                final var callback = agent.mulliganDecision();
                final String requestId = callback.register(keep -> {
                    // MCP runs on another thread; publish the choice without touching the game.
                    decision.complete(keep);
                    return "Mulligan decision accepted.";
                });
                try {
                    agent.prompt(MulliganPrompt.render(player, firstPlayer, cardsToReturn, requestId));
                } catch (RuntimeException e) {
                    Logger.error(e, "Could not request a mulligan decision for {}", player.getName());
                } finally {
                    if (callback.cancel(requestId)) {
                        decision.complete(null);
                    }
                }
                // If the tool already claimed the callback, wait for it to publish its result.
                final Boolean keep = decision.join();
                if (keep != null) {
                    return keep;
                }
                Logger.warn("No mulligan decision submitted for {}; using the built-in AI", player.getName());
            } catch (RuntimeException e) {
                Logger.error(e, "Could not request a mulligan decision for {}", player.getName());
            }
        }
        return super.mulliganKeepHand(firstPlayer, cardsToReturn);
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        if (getGame().isSimulationCopy()) return super.chooseSpellAbilityToPlay();
        if (passAfterFailedPlay) {
            passAfterFailedPlay = false;
            return null;
        }
        if (agent == null) {
            return null;
        }
        try {
            // Candidate affordability probes may invoke controller callbacks (e.g. convoke).
            // They must use local decisions rather than starting speculative harness requests.
            final var options = locally(() -> PriorityActions.available(player));
            if (options.isEmpty()) {
                return null;
            }
            final var decision = new CompletableFuture<PriorityDecision>();
            final var callback = agent.priorityDecision();
            final String requestId = callback.register(choice -> {
                if (choice instanceof PriorityDecision.PlayAction play && options.containsKey(play)
                        || choice instanceof PriorityDecision.Pass) {
                    return;
                }
                throw new IllegalArgumentException("Choose a cardId and abilityId pair from the priority prompt, or pass.");
            }, choice -> {
                // Only publish IDs here; all game objects stay on the game thread.
                decision.complete(choice);
                return "Priority decision accepted. Finish your response so Forge can execute it.";
            });
            try {
                agent.prompt(PriorityPrompt.render(player, requestId, options));
            } catch (RuntimeException e) {
                Logger.error(e, "Could not request a priority decision for {}", player.getName());
            } finally {
                if (callback.cancel(requestId)) {
                    decision.complete(new PriorityDecision.Pass());
                }
            }
            if (decision.join() instanceof PriorityDecision.PlayAction play) {
                return List.of(options.get(play));
            }
        } catch (RuntimeException e) {
            Logger.error(e, "Could not choose a play for {}; passing priority", player.getName());
        }
        return null;
    }

    @Override
    public boolean playChosenSpellAbility(SpellAbility ability) {
        boolean played = PlaySpellAbility.playSpellAbility(this, player, ability);
        if (!played) {
            passAfterFailedPlay = true;
            notifyOfValue(ability, null, "The previous play failed or was canceled: " + ability.getDescription());
        }
        return played;
    }
}
