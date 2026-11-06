package forge.ai.llm;

import com.google.common.eventbus.Subscribe;
import forge.LobbyPlayer;
import forge.ai.llm.tools.SubmitSideboardPlan;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameType;
import forge.game.event.GameEventGameFinished;
import forge.game.event.GameEventGameStarted;
import forge.game.player.Player;
import forge.game.player.PlaySpellAbility;
import forge.game.spellability.SpellAbility;
import forge.item.PaperCard;
import org.tinylog.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Harness-controlled gameplay with validated choices and local AI fallback.
 */
public class PlayerControllerLlm extends LlmDecisionController {
    private final Supplier<AcpAgent> agentFactory;
    private AcpAgent agent;
    private boolean initializationAttempted;
    private boolean finished;
    private boolean passAfterFailedPlay;
    private List<String> sideboardingReport = List.of();

    @Override
    protected AcpAgent decisionAgent() {
        return agent;
    }

    public PlayerControllerLlm(Game game, Player player, LobbyPlayer lobbyPlayer) {
        this(game, player, lobbyPlayer, () -> AcpAgent.start(Path.of("."), player));
    }

    public PlayerControllerLlm(Game game, Player player, LobbyPlayer lobbyPlayer, String agentId) {
        this(game, player, lobbyPlayer, () -> AcpAgent.start(Path.of("."), player, agentId));
    }

    PlayerControllerLlm(Game game, Player player, LobbyPlayer lobbyPlayer, Supplier<AcpAgent> agentFactory) {
        super(game, player, lobbyPlayer);
        this.agentFactory = agentFactory;
        game.subscribeToEvents(this);
    }

    @Subscribe
    public void onGameStarted(GameEventGameStarted event) {
        initializeAgent();
        // A session started for sideboarding must now publish the deck actually prepared by Match.
        if (agent != null) agent.refreshGameState();
    }

    private void initializeAgent() {
        // Copies may advance phases for simulations; they must not start agents.
        if (getGame().isSimulationCopy() || initializationAttempted || finished) {
            return;
        }
        LlmMatchProfile.forGame(getGame());
        initializationAttempted = true;
        try {
            agent = agentFactory.get();
            Logger.info("Initialized ACP for {} with {} (reasoning effort: {})", player.getName(),
                    agent.options().model(), agent.options().reasoningEffort());
        } catch (RuntimeException e) {
            Logger.error(e, "Could not initialize ACP for {}", player.getName());
        }
    }

    @Override
    public boolean sideboardsByItself() {
        return getGame().getRules().getAISideboardingEnabled();
    }

    @Override
    public List<String> getSideboardingReport() {
        return sideboardingReport;
    }

    @Override
    public List<PaperCard> sideboard(Deck deck, GameType gameType, String message) {
        sideboardingReport = List.of();
        if (!sideboardsByItself() || !gameType.isSideboardingAllowed() || getGame().isSimulationCopy() || finished) {
            return null;
        }
        final var options = new SideboardOptions(deck, gameType);
        if (!options.hasChoices()) return null;
        initializeAgent();
        if (agent != null) {
            try (var timing = agent.profile().scope("decision", "Sideboarding")) {
                final var decision = new CompletableFuture<SubmitSideboardPlan>();
                final var callback = agent.sideboardDecision();
                final String requestId = callback.register(options::validate, plan -> {
                    // MCP validates frozen counts and publishes IDs; it never changes the deck.
                    agent.profile().accepted("submit_sideboard_plan");
                    decision.complete(plan);
                    return "Sideboarding plan accepted.";
                });
                final var match = getGame().getMatch();
                final String score = match.getPlayers().stream()
                        .map(participant -> participant.getPlayer().getName() + "="
                                + match.getGamesWonBy(participant.getPlayer()))
                        .collect(java.util.stream.Collectors.joining(", "));
                try {
                    agent.prompt(options.prompt(player.getName(), requestId, score),
                            requestId, "sideboard", "Sideboarding", decision);
                } catch (RuntimeException e) {
                    Logger.error(e, "Could not request sideboarding for {}", player.getName());
                } finally {
                    if (callback.cancel(requestId)) decision.complete(null);
                }
                final var plan = decision.join();
                if (plan != null) {
                    sideboardingReport = options.report(plan);
                    return options.apply(plan);
                }
            } catch (RuntimeException e) {
                Logger.error(e, "Could not request sideboarding for {}", player.getName());
            }
            agent.profile().fallback("Sideboarding");
        }
        sideboardingReport = List.of("kept the same deck (no LLM sideboarding decision).");
        onLlmFallback("Sideboarding");
        return null;
    }

    @Subscribe
    public void onGameFinished(GameEventGameFinished event) {
        finished = true;
        if (agent != null) {
            agent.profile().endAction("game_finished");
            agent.close();
            agent = null;
        }
    }

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        if (agent == null || getGame().isSimulationCopy()) return mulliganDecision(firstPlayer, cardsToReturn);
        try (var timing = agent.profile().scope("decision", "Mulligan")) {
            return mulliganDecision(firstPlayer, cardsToReturn);
        }
    }

    private boolean mulliganDecision(Player firstPlayer, int cardsToReturn) {
        if (agent != null && !getGame().isSimulationCopy()) {
            try {
                final var decision = new CompletableFuture<Boolean>();
                final var callback = agent.mulliganDecision();
                final String requestId = callback.register(keep -> {
                    // MCP runs on another thread; publish the choice without touching the game.
                    agent.profile().accepted("submit_mulligan_decision");
                    decision.complete(keep);
                    return "Mulligan decision accepted.";
                });
                try {
                    agent.prompt(MulliganPrompt.render(player, firstPlayer, cardsToReturn, requestId),
                            requestId, "mulligan", "Mulligan", decision);
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
                agent.profile().fallback("Mulligan");
                Logger.warn("No mulligan decision submitted for {}; using the built-in AI", player.getName());
            } catch (RuntimeException e) {
                Logger.error(e, "Could not request a mulligan decision for {}", player.getName());
            }
        }
        return fallbackMulliganKeepHand(firstPlayer, cardsToReturn);
    }

    /** Override the fallback policy when a caller requires an actual model decision, such as a live eval. */
    protected boolean fallbackMulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        return super.mulliganKeepHand(firstPlayer, cardsToReturn);
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        if (agent == null || getGame().isSimulationCopy() || passAfterFailedPlay) return chooseLlmAction();
        final var profile = agent.profile();
        profile.beginAction("Priority action");
        boolean selected = false;
        try {
            final var result = chooseLlmAction();
            selected = result != null && !result.isEmpty();
            return result;
        } finally {
            if (!selected) profile.endAction("pass");
        }
    }

    private List<SpellAbility> chooseLlmAction() {
        if (getGame().isSimulationCopy()) return super.chooseSpellAbilityToPlay();
        if (passAfterFailedPlay) {
            passAfterFailedPlay = false;
            return null;
        }
        if (agent == null) {
            return null;
        }
        boolean fallbackRecorded = false;
        try {
            // Preparing alternative actions must not start speculative harness requests.
            final Map<PriorityDecision.PlayAction, SpellAbility> options;
            try (var preparation = agent.profile().scope("preparation", "Enumerate priority actions")) {
                options = locally(() -> PriorityActions.available(player));
                preparation.data("optionCount", options.size());
            }
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
                throw new IllegalArgumentException("Choose a cardId and abilityId pair returned by get_available_actions, or pass.");
            }, choice -> {
                // Only publish IDs here; all game objects stay on the game thread.
                agent.profile().accepted(choice instanceof PriorityDecision.PlayAction ? "play_action" : "pass_priority");
                decision.complete(choice);
                return "Priority decision accepted.";
            });
            try {
                try (var preparation = agent.profile().scope("preparation", "Capture priority options")) {
                    agent.setPriorityActions(PriorityActionsSnapshot.capture(player, requestId, options));
                }
                agent.prompt(PriorityPrompt.render(player, requestId), requestId, "priority", "Choose priority action", decision);
            } catch (RuntimeException e) {
                Logger.error(e, "Could not request a priority decision for {}", player.getName());
            } finally {
                agent.clearPriorityActions(requestId);
                if (callback.cancel(requestId)) {
                    decision.complete(new PriorityDecision.Pass());
                    agent.profile().fallback("Choose a priority action");
                    fallbackRecorded = true;
                    onLlmFallback("Choose a priority action");
                }
            }
            final var selected = decision.join();
            if (selected instanceof PriorityDecision.PlayAction play) {
                agent.profile().actionName(options.get(play).getDescription());
                return List.of(options.get(play));
            }
        } catch (RuntimeException e) {
            Logger.error(e, "Could not choose a play for {}; passing priority", player.getName());
            if (!fallbackRecorded) agent.profile().fallback("Choose a priority action: " + e.getMessage());
            onLlmFallback("Choose a priority action: " + e.getMessage());
        }
        return null;
    }

    @Override
    public boolean playChosenSpellAbility(SpellAbility ability) {
        if (agent == null || getGame().isSimulationCopy()) return executeChosenAbility(ability);
        final var profile = agent.profile();
        String outcome = "error";
        try (var execution = profile.scope("execution", ability.getDescription())) {
            final boolean played = executeChosenAbility(ability);
            outcome = played ? "played" : passAfterFailedPlay ? "failed" : "canceled";
            execution.data("outcome", outcome);
            return played;
        } finally {
            profile.endAction(outcome);
        }
    }

    private boolean executeChosenAbility(SpellAbility ability) {
        beginPriorityAction();
        try {
            boolean played = PlaySpellAbility.playSpellAbility(this, player, ability);
            if (!played) {
                // Deliberate cancellation keeps priority. Unanswered/failed execution still
                // passes once to avoid retrying an unpayable action indefinitely.
                passAfterFailedPlay = !actionWasCanceled();
                notifyOfValue(ability, null, actionWasCanceled()
                        ? "The previous action was canceled. You still have priority: " + ability.getDescription()
                        : "The previous play failed or was canceled: " + ability.getDescription());
            }
            return played;
        } finally {
            endPriorityAction();
        }
    }
}
