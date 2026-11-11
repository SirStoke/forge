package forge.ai.llm;

import com.google.common.collect.ListMultimap;
import forge.LobbyPlayer;
import forge.StaticData;
import forge.ai.PlayerControllerAi;
import forge.ai.llm.tools.SubmitChoice;
import forge.ai.llm.tools.PayMana;
import forge.card.ColorSet;
import forge.card.ICardFace;
import forge.card.MagicColor;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.*;
import forge.game.ability.effects.RollDiceEffect;
import forge.game.card.*;
import forge.game.card.sticker.Sticker;
import forge.game.combat.Combat;
import forge.game.cost.*;
import forge.game.keyword.KeywordInterface;
import forge.game.mana.*;
import forge.game.player.*;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.*;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.PlayerZone;
import forge.game.zone.ZoneType;
import forge.util.ITriggerEvent;
import forge.util.Localizer;
import forge.util.collect.FCollectionView;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.tinylog.Logger;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Controller-facing decision primitives. All engine access and validation stays on the game thread. */
abstract class LlmDecisionController extends PlayerControllerAi {
    private final Set<Integer> temporaryCards = new HashSet<>();
    private final List<String> notices = new ArrayList<>();
    private int localDecisions;
    private int manaPaymentDepth;
    private boolean preparingPriorityAction;
    private boolean actionCanceled;

    final void beginPriorityAction() {
        preparingPriorityAction = true;
        actionCanceled = false;
    }

    final void endPriorityAction() {
        preparingPriorityAction = false;
        actionCanceled = false;
    }

    final boolean actionWasCanceled() {
        return actionCanceled;
    }

    LlmDecisionController(Game game, Player player, LobbyPlayer lobbyPlayer) {
        super(game, player, lobbyPlayer);
    }

    protected abstract AcpAgent decisionAgent();

    /** Allows live evals to reject a missing model decision instead of accepting local AI behavior. */
    protected void onLlmFallback(String title) { }

    final boolean usesLlm() {
        return decisionAgent() != null && !getGame().isSimulationCopy() && localDecisions == 0;
    }

    final <T> T locally(Supplier<T> action) {
        localDecisions++;
        try { return action.get(); }
        finally { localDecisions--; }
    }

    private SubmitChoice request(String title, List<String> labels, int min, int max, boolean repeat,
                                 boolean number, boolean cancellable) {
        final ChoiceDecision decision = requestDecision(title, labels, min, max, repeat, number, cancellable, null);
        return decision instanceof ChoiceDecision.Choose chosen ? chosen.answer() : null;
    }

    final ChoiceDecision requestManaPayment(SpellAbility sa, String title, List<String> labels, List<Boolean> manaSources) {
        if (preparingPriorityAction && actionCanceled) return null;
        return requestDecision(context(sa, title), labels, 0, Math.min(1, labels.size()), false, false, true, List.copyOf(manaSources));
    }

    private ChoiceDecision requestDecision(String title, List<String> labels, int min, int max, boolean repeat,
                                           boolean number, boolean cancellable, List<Boolean> manaSources) {
        try (var timing = decisionAgent().profile().scope("decision", title)) {
            return requestDecisionImpl(title, labels, min, max, repeat, number, cancellable, manaSources);
        }
    }

    private ChoiceDecision requestDecisionImpl(String title, List<String> labels, int min, int max, boolean repeat,
                                               boolean number, boolean cancellable, List<Boolean> manaSources) {
        final boolean canCancel = cancellable && preparingPriorityAction;
        var future = new CompletableFuture<ChoiceDecision>();
        var callback = decisionAgent().choiceDecision();
        // The validator captures only immutable labels and scalar bounds, never live engine objects.
        int size = labels.size();
        String id = callback.register(decision -> {
            if (decision instanceof ChoiceDecision.Cancel) {
                if (!canCancel) throw new IllegalArgumentException("This prompt does not allow cancel_action.");
                return;
            }
            if (decision instanceof ChoiceDecision.Payment payment) {
                if (manaSources == null) throw new IllegalArgumentException("This prompt does not allow pay_mana.");
                for (PayMana.Step step : payment.plan().steps()) {
                    if (step.option() >= size) throw new IllegalArgumentException("Choose offered payment option indices.");
                    if (!step.colors().isEmpty() && !manaSources.get(step.option())) {
                        throw new IllegalArgumentException("colors is only valid for a mana-source activation.");
                    }
                }
                return;
            }
            SubmitChoice answer = ((ChoiceDecision.Choose) decision).answer();
            if (number) {
                if (answer.choices() != null || answer.value() == null || answer.value() < min || answer.value() > max) {
                    throw new IllegalArgumentException("Supply value between " + min + " and " + max);
                }
            } else if (answer.value() != null || answer.choices() == null || answer.choices().size() < min
                    || answer.choices().size() > max || answer.choices().stream().anyMatch(i -> i < 0 || i >= size)
                    || !repeat && new HashSet<>(answer.choices()).size() != answer.choices().size()) {
                throw new IllegalArgumentException("Supply " + min + ".." + max + " offered indices"
                        + (repeat ? " (repetition allowed)" : " without duplicates"));
            }
        }, answer -> {
            decisionAgent().profile().accepted(answer instanceof ChoiceDecision.Cancel ? "cancel_action"
                    : answer instanceof ChoiceDecision.Payment ? "pay_mana" : "submit_choice");
            future.complete(answer);
            return answer instanceof ChoiceDecision.Cancel
                    ? "Action cancellation accepted. Forge will offer another action without passing priority."
                    : answer instanceof ChoiceDecision.Payment
                    ? "Payment plan accepted."
                    : "Choice accepted.";
        });
        StringBuilder prompt = new StringBuilder("You are playing as ").append(player.getName())
                .append(".\nRequest ID: ").append(id).append("\nDecision: ").append(title)
                .append("\nProtocol: choice (session instructions).\n");
        prompt.append("Phase: ").append(getGame().getPhaseHandler().getPhase()).append('\n');
        for (Player p : getGame().getPlayers()) {
            prompt.append("Player: ").append(describe(p)).append(" counters=").append(p.getCounters())
                    .append(" handSize=").append(p.getCardsIn(ZoneType.Hand).size())
                    .append(" librarySize=").append(p.getCardsIn(ZoneType.Library).size()).append('\n');
        }
        for (String notice : notices) {
            prompt.append("Revealed information: ").append(notice).append('\n');
        }
        notices.clear();
        if (number) {
            prompt.append("Answer: value\nRange: ").append(min).append("..").append(max).append('\n');
        } else {
            prompt.append("Answer: choices\nCount: ").append(min).append("..").append(max)
                    .append("\nRepetition: ").append(repeat ? "allowed" : "forbidden").append('\n');
        }
        if (manaSources != null) prompt.append("Payment: pay_mana\n");
        prompt.append("Cancel action: ").append(canCancel ? "allowed" : "forbidden").append('\n');
        for (int i = 0; i < labels.size(); i++) {
            prompt.append(i).append(": ").append(labels.get(i)).append('\n');
        }
        try {
            final String kind = manaSources != null ? "payment" : number ? "number"
                    : title.toLowerCase(java.util.Locale.ROOT).contains("target") ? "targeting" : "choice";
            decisionAgent().prompt(prompt.toString(), id, kind, title, future);
        } catch (RuntimeException e) {
            Logger.warn(e, "LLM prompt failed for {}: {}", player, title);
        } finally {
            if (callback.cancel(id)) {
                future.complete(null);
            }
        }
        final ChoiceDecision decision = future.join();
        if (decision instanceof ChoiceDecision.Cancel) {
            // Set only on the game thread, after submission. No fallback may complete this canceled choice.
            actionCanceled = true;
            return null;
        }
        return decision;
    }

    final <T> List<T> select(String title, List<T> options, int min, int max, boolean repeat,
                              Predicate<List<T>> valid, Supplier<List<T>> fallback) {
        return select(title, options, min, max, repeat, valid, fallback, false);
    }

    /** Only use at an engine boundary that treats null as an aborted cast/payment. */
    final <T> List<T> selectCancelable(String title, List<T> options, int min, int max, boolean repeat,
                                     Predicate<List<T>> valid, Supplier<List<T>> fallback) {
        return select(title, options, min, max, repeat, valid, fallback, true);
    }

    private <T> List<T> select(String title, List<T> options, int min, int max, boolean repeat,
                               Predicate<List<T>> valid, Supplier<List<T>> fallback, boolean cancellable) {
        if (cancellable && preparingPriorityAction && actionCanceled) return null;
        if (!usesLlm()) {
            return fallback.get();
        }
        final List<T> offered = List.copyOf(options);
        max = repeat && !offered.isEmpty() ? max : Math.min(max, offered.size());
        if (min < 0 || max < min) {
            throw new IllegalArgumentException("Impossible choice bounds for " + title);
        }
        // No decision exists when nothing can be selected or exactly one forced option exists.
        // An empty payment menu still needs a cancellation prompt during action preparation.
        if (max == 0 && !(cancellable && preparingPriorityAction)
                || offered.size() == 1 && min == 1 && max == 1) {
            List<T> forced = max == 0 ? new ArrayList<>() : new ArrayList<>(offered);
            if (valid.test(forced)) return forced;
        }
        List<String> labels = offered.stream().map(this::describe).toList();
        for (int attempt = 0; attempt < 3; attempt++) {
            SubmitChoice answer = request(title, labels, min, max, repeat, false, cancellable);
            if (answer == null) {
                if (cancellable && preparingPriorityAction && actionCanceled) return null;
                break;
            }
            List<T> selected = new ArrayList<>();
            answer.choices().forEach(index -> selected.add(offered.get(index)));
            if (valid.test(selected)) return selected;
            title = title + "\nThe previous combination was illegal. Choose a different legal combination.";
        }
        Logger.warn("Using local fallback for {}: {}", player, title);
        if (decisionAgent() != null) decisionAgent().profile().fallback(title);
        onLlmFallback(title);
        return locally(fallback);
    }

    final <T> List<T> select(String title, Iterable<T> options, int min, int max, Supplier<List<T>> fallback) {
        List<T> list = new ArrayList<>();
        options.forEach(list::add);
        return select(title, list, min, max, false, chosen -> true, fallback);
    }

    final <T> List<T> selectCancelable(String title, Iterable<T> options, int min, int max, Supplier<List<T>> fallback) {
        List<T> list = new ArrayList<>();
        options.forEach(list::add);
        return selectCancelable(title, list, min, max, false, chosen -> true, fallback);
    }

    final <T> T oneCancelable(String title, Iterable<T> options, boolean optional, Supplier<T> fallback) {
        if (!options.iterator().hasNext() && !(optional && preparingPriorityAction && usesLlm())) return null;
        List<T> selected = selectCancelable(title, options, optional ? 0 : 1, 1, () -> {
            T result = fallback.get();
            return result == null ? List.of() : List.of(result);
        });
        return selected == null || selected.isEmpty() ? null : selected.get(0);
    }

    final <T> T one(String title, Iterable<T> options, boolean optional, Supplier<T> fallback) {
        if (!options.iterator().hasNext()) return null;
        List<T> selected = select(title, options, optional ? 0 : 1, 1, () -> {
            T result = fallback.get();
            return result == null ? List.of() : List.of(result);
        });
        return selected.isEmpty() ? null : selected.get(0);
    }

    final int number(String title, int min, int max, Supplier<Integer> fallback) {
        if (!usesLlm()) return fallback.get();
        if (min == max) return min;
        if (min > max) throw new IllegalArgumentException("Impossible number bounds: " + title);
        SubmitChoice answer = request(title, List.of(), min, max, false, true, false);
        if (answer != null) return answer.value();
        Logger.warn("Using local numeric fallback for {}: {}", player, title);
        if (decisionAgent() != null) decisionAgent().profile().fallback(title);
        onLlmFallback(title);
        return locally(fallback);
    }

    final boolean yes(String title, Supplier<Boolean> fallback) {
        return one(title, List.of("Yes", "No"), false, () -> fallback.get() ? "Yes" : "No").equals("Yes");
    }

    final String context(SpellAbility ability, String title) {
        return ability == null ? title : title + "\nAbility: " + describe(ability);
    }

    final String describe(Object object) {
        if (object instanceof Card card) {
            boolean visible = temporaryCards.contains(card.getId())
                    || card.getView().canBeShownTo(player.getView()) && card.getView().canFaceDownBeShownTo(player.getView());
            if (!visible) return "Hidden card id=" + card.getId() + " zone=" + card.getZone().getZoneType();
            return card.getName() + " id=" + card.getId() + " controller=" + card.getController().getName()
                    + " type=" + card.getType() + " manaCost=" + card.getManaCost()
                    + " P/T=" + card.getNetPower() + "/" + card.getNetToughness()
                    + " tapped=" + card.isTapped() + " damage=" + card.getDamage() + " counters=" + card.getCounters();
        }
        if (object instanceof Player p) return p.getName() + " id=" + p.getId() + " life=" + p.getLife();
        if (object instanceof SpellAbility sa) {
            // Engine rules such as the legend rule use EmptySa with an ownerless placeholder card.
            if (sa instanceof SpellAbility.EmptySa && sa.getHostCard().getController() == null) {
                return "Game rule" + (sa.getApi() == null ? "" : " " + sa.getApi())
                        + (sa.getDescription().isEmpty() ? "" : ": " + sa.getDescription());
            }
            return describe(sa.getHostCard()) + ": " + sa.getDescription();
        }
        if (object instanceof ICardFace face) return face.getName();
        return String.valueOf(object);
    }

    final CardCollection cards(String title, CardCollectionView options, int min, int max,
                               Supplier<CardCollectionView> fallback) {
        return new CardCollection(select(title, options, min, max, () -> {
            CardCollectionView result = fallback.get();
            return result == null ? List.of() : new ArrayList<>(result);
        }));
    }

    @Override
    public SpellAbility getAbilityToPlay(Card card, List<SpellAbility> abilities, ITriggerEvent event) {
        return oneCancelable("Choose an ability/casting cost", abilities, false, () -> super.getAbilityToPlay(card, abilities, event));
    }

    @Override
    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible, int min, int max, boolean repeat) {
        return selectCancelable(context(sa, "Choose modes" + (sa.hasParam("Pawprint") ? "; total pawprint budget=" + max : "")), possible, min, max, repeat,
                selected -> !sa.hasParam("Pawprint") || selected.stream().mapToInt(mode ->
                        forge.game.ability.AbilityUtils.calculateAmount(mode.getHostCard(), mode.getParam("Pawprint"), mode)).sum() <= max,
                () -> super.chooseModeForAbility(sa, possible, min, max, repeat));
    }

    @Override
    public CardCollectionView chooseCardsForEffect(CardCollectionView options, SpellAbility sa, String title,
            int min, int max, boolean optional, Map<String, Object> params) {
        if (!usesLlm()) return super.chooseCardsForEffect(options, sa, title, min, max, optional, params);
        return withShown(options, () -> new CardCollection(select(context(sa, title), new ArrayList<>(options), optional ? 0 : Math.min(min, options.size()), max,
                false, chosen -> chosen.isEmpty() && optional || chosen.size() >= Math.min(min, options.size()),
                () -> new ArrayList<>(super.chooseCardsForEffect(options, sa, title, min, max, optional, params)))));
    }

    @Override
    public CardCollection chooseCardsForEffectMultiple(Map<String, CardCollection> options, SpellAbility sa,
                                                       String title, boolean optional) {
        if (!usesLlm()) return super.chooseCardsForEffectMultiple(options, sa, title, optional);
        CardCollection result = new CardCollection();
        options.forEach((key, cards) -> result.addAll(chooseCardsForEffect(cards, sa, title + " (" + key + ")", 0, 1, optional, null)));
        return result;
    }

    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> options, DelayedReveal reveal,
            SpellAbility sa, String title, boolean optional, Player related, Map<String, Object> params) {
        if (reveal != null) reveal(reveal);
        return withShown(options, () -> one(context(sa, title), options, optional,
                () -> super.chooseSingleEntityForEffect(options, null, sa, title, optional, related, params)));
    }

    @Override
    public <T extends GameEntity> List<T> chooseEntitiesForEffect(FCollectionView<T> options, int min, int max,
            DelayedReveal reveal, SpellAbility sa, String title, Player related, Map<String, Object> params) {
        if (reveal != null) reveal(reveal);
        return withShown(options, () -> select(context(sa, title), options, Math.min(min, options.size()), max,
                () -> super.chooseEntitiesForEffect(options, min, max, null, sa, title, related, params)));
    }

    @Override
    public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa,
            CardCollection options, DelayedReveal reveal, String title, boolean optional, Player decider) {
        if (!usesLlm()) return super.chooseSingleCardForZoneChange(destination, origin, sa, options, reveal, title, optional, decider);
        if (reveal != null) reveal(reveal);
        // A library search explicitly authorizes seeing this offered search pool, not the entire library.
        Set<Integer> previous = new HashSet<>(temporaryCards);
        if (origin.contains(ZoneType.Library)) options.forEach(c -> temporaryCards.add(c.getId()));
        try {
            return one(context(sa, title), options, optional,
                    () -> super.chooseSingleCardForZoneChange(destination, origin, sa, options, null, title, optional, decider));
        } finally {
            temporaryCards.clear();
            temporaryCards.addAll(previous);
        }
    }

    @Override
    public List<Card> chooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa,
            CardCollection options, int min, int max, DelayedReveal reveal, String title, Player decider) {
        if (!usesLlm()) return super.chooseCardsForZoneChange(destination, origin, sa, options, min, max, reveal, title, decider);
        if (reveal != null) reveal(reveal);
        Set<Integer> previous = new HashSet<>(temporaryCards);
        if (origin.contains(ZoneType.Library)) options.forEach(c -> temporaryCards.add(c.getId()));
        try {
            return select(context(sa, title), options, min, max, () -> new ArrayList<>(options).subList(0, min));
        } finally {
            temporaryCards.clear();
            temporaryCards.addAll(previous);
        }
    }

    @Override
    public CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max, CardCollectionView options, String title) {
        return cards(context(sa, title), options, min, max, () -> super.choosePermanentsToSacrifice(sa, min, max, options, title));
    }

    @Override
    public CardCollectionView choosePermanentsToDestroy(SpellAbility sa, int min, int max, CardCollectionView options, String title) {
        return cards(context(sa, title), options, min, max, () -> super.choosePermanentsToDestroy(sa, min, max, options, title));
    }

    @Override
    public CardCollection chooseCardsToDiscardFrom(Player p, SpellAbility sa, CardCollection options, int min, int max, CardCollectionView visible) {
        if (visible != null) tempShowCards(visible);
        try {
            return cards(context(sa, "Choose cards to discard"), options, min, max,
                    () -> super.chooseCardsToDiscardFrom(p, sa, options, min, max, visible));
        } finally { endTempShowCards(); }
    }

    @Override
    public CardCollectionView chooseCardsToDiscardToMaximumHandSize(int count) {
        return cards("Discard to maximum hand size", player.getCardsIn(ZoneType.Hand), count, count,
                () -> super.chooseCardsToDiscardToMaximumHandSize(count));
    }

    @Override
    public CardCollectionView chooseCardsToDiscardUnlessType(int count, CardCollectionView hand, String[] types, SpellAbility sa) {
        if (!usesLlm()) return super.chooseCardsToDiscardUnlessType(count, hand, types, sa);
        CardCollection matching = CardLists.getValidCards(hand, types, sa.getActivatingPlayer(), sa.getHostCard(), sa);
        List<String> options = new ArrayList<>();
        if (!matching.isEmpty()) options.add("Discard one card of the required type");
        if (hand.size() >= count) options.add("Discard " + count + " cards");
        String choice = one(context(sa, "Choose how to discard"), options, false, () -> options.get(0));
        return choice.equals("Discard one card of the required type")
                ? cards("Discard a card of the required type", matching, 1, 1, () -> new CardCollection(matching.getFirst()))
                : cards("Discard cards", hand, Math.min(count, hand.size()), Math.min(count, hand.size()), () -> super.chooseCardsToDiscardUnlessType(count, hand, types, sa));
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int count) {
        return cards("Choose cards to return for the mulligan", hand, count, count, () -> super.tuckCardsViaMulligan(hand, count));
    }

    @Override
    public CardCollectionView chooseCardsToRevealFromHand(int min, int max, CardCollectionView options) {
        return cards("Choose cards to reveal", options, min, max, () -> super.chooseCardsToRevealFromHand(min, max, options));
    }

    @Override
    public CardCollectionView chooseCardsToDelve(int generic, CardCollection grave) {
        CardCollection legal = CardLists.filter(grave, card -> card.canExiledBy(player.getPaidForSA(), false));
        return cards("Choose cards to exile for delve", legal, 0, Math.min(generic, legal.size()), () -> super.chooseCardsToDelve(generic, legal));
    }

    @Override
    public List<Card> chooseCardsForSplice(SpellAbility sa, List<Card> options) {
        return select(context(sa, "Choose cards to splice"), options, 0, options.size(), () -> super.chooseCardsForSplice(sa, options));
    }

    @Override
    public CardCollectionView orderMoveToZoneList(CardCollectionView options, ZoneType destination, SpellAbility sa) {
        if (sa != null && sa.getApi() == forge.game.ability.ApiType.RearrangeTopOfLibrary) {
            // This effect authorizes looking at these library cards. Each selected card is inserted at index zero.
            return withShown(options, () -> cards(context(sa, "Reorder the revealed library cards; "
                            + "the LAST selected card will be on top, the first selected card deepest"), options,
                    options.size(), options.size(), () -> super.orderMoveToZoneList(options, destination, sa)));
        }
        return cards(context(sa, "Order cards moving to " + destination + "; first in the list moves first"), options,
                options.size(), options.size(), () -> super.orderMoveToZoneList(options, destination, sa));
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection options) {
        return arrangeLibrary(options, "Scry", () -> super.arrangeForScry(options));
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection options) {
        return arrangeLibrary(options, "Surveil", () -> super.arrangeForSurveil(options));
    }

    private ImmutablePair<CardCollection, CardCollection> arrangeLibrary(CardCollection options, String title,
            Supplier<ImmutablePair<CardCollection, CardCollection>> fallback) {
        if (!usesLlm()) return fallback.get();
        tempShowCards(options);
        try {
            CardCollection other = cards(title + ": choose cards to move " + (title.equals("Scry") ? "to bottom" : "to graveyard"),
                    options, 0, options.size(), () -> fallback.get().getRight());
            CardCollection top = new CardCollection(options);
            top.removeAll(other);
            CardCollection kept = top;
            top = cards(title + ": order cards to keep on top (topmost first)", top, top.size(), top.size(), () -> kept);
            return ImmutablePair.of(top, other);
        } finally { endTempShowCards(); }
    }

    @Override
    public boolean willPutCardOnTop(Card card) {
        return yes("Put " + describe(card) + " on top?", () -> super.willPutCardOnTop(card));
    }

    @Override
    public Integer announceRequirements(SpellAbility sa, int min, int max, String title) {
        if (!usesLlm()) return super.announceRequirements(sa, min, max, title);
        if (min == max) return min;
        if (min > max) throw new IllegalArgumentException("Impossible announcement bounds: " + title);
        SubmitChoice answer = request(context(sa, "Announce " + title), List.of(), min, max, false, true, true);
        if (actionCanceled && preparingPriorityAction) return null;
        return answer != null ? answer.value() : locally(() -> super.announceRequirements(sa, min, max, title));
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        return number(context(sa, title), min, max, () -> sa == null ? min : super.chooseNumber(sa, title, min, max));
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max, Map<String, Object> params) {
        return number(context(sa, title), min, max, () -> sa == null ? min : super.chooseNumber(sa, title, min, max, params));
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, List<Integer> options, Player related) {
        return one(context(sa, title), options, false, () -> super.chooseNumber(sa, title, options, related));
    }

    @Override
    public int chooseNumberForCostReduction(SpellAbility sa, int min, int max) {
        return number(context(sa, "Choose cost reduction amount"), min, max, () -> super.chooseNumberForCostReduction(sa, min, max));
    }

    @Override
    public int chooseNumberForKeywordCost(SpellAbility sa, Cost cost, KeywordInterface keyword, String title, int max) {
        return number(context(sa, title + ": " + cost), 0, max, () -> super.chooseNumberForKeywordCost(sa, cost, keyword, title, max));
    }

    @Override
    public boolean chooseBinary(SpellAbility sa, String title, BinaryChoiceType type, Boolean defaultChoice) {
        return yes(context(sa, title + " (Yes selects the first alternative in " + type + ")"),
                () -> super.chooseBinary(sa, title, type, defaultChoice));
    }

    @Override
    public boolean chooseBinary(SpellAbility sa, String title, BinaryChoiceType type, Map<String, Object> params) {
        return chooseBinary(sa, title, type, (Boolean) null);
    }

    @Override
    public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String title, List<String> options, Card show, Map<String, Object> params) {
        return yes(context(sa, title + (show == null ? "" : " " + describe(show))), () -> super.confirmAction(sa, mode, title, options, show, params));
    }

    @Override
    public boolean confirmPayment(CostPart cost, String title, SpellAbility sa) {
        return "Yes".equals(oneCancelable(context(sa, title + " Cost: " + cost), List.of("Yes", "No"), false,
                () -> super.confirmPayment(cost, title, sa) ? "Yes" : "No"));
    }

    @Override
    public boolean confirmBidAction(SpellAbility sa, PlayerActionConfirmMode mode, String title, int bid, Player winner) {
        return yes(context(sa, title + " bid=" + bid), () -> super.confirmBidAction(sa, mode, title, bid, winner));
    }

    @Override
    public boolean confirmStaticApplication(Card card, PlayerActionConfirmMode mode, String title, String logic) {
        return yes(title, () -> super.confirmStaticApplication(card, mode, title, logic));
    }

    @Override
    public boolean confirmReplacementEffect(ReplacementEffect effect, SpellAbility sa, GameEntity affected, String title) {
        return yes(context(sa, title), () -> super.confirmReplacementEffect(effect, sa, affected, title));
    }

    @Override
    public boolean confirmTrigger(WrappedAbility sa) {
        return yes(context(sa, "Use this optional trigger?"), () -> super.confirmTrigger(sa));
    }

    @Override
    public List<OptionalCostValue> chooseOptionalCosts(SpellAbility sa, List<OptionalCostValue> options) {
        return selectCancelable(context(sa, "Choose optional additional costs"), options, 0, options.size(), () -> super.chooseOptionalCosts(sa, options));
    }

    @Override
    public List<CostPart> orderCosts(List<CostPart> options) {
        return select("Order cost payments (first paid first)", options, options.size(), options.size(), () -> super.orderCosts(options));
    }

    @Override
    public String chooseSomeType(String kind, SpellAbility sa, Collection<String> options, boolean optional) {
        return one(context(sa, "Choose " + kind), options, optional, () -> super.chooseSomeType(kind, sa, options, optional));
    }

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, List<ICardFace> options, String title) {
        return one(context(sa, title), options, false, () -> super.chooseSingleCardFace(sa, options, title));
    }

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, String title, Predicate<ICardFace> predicate, String name) {
        if (!usesLlm()) return super.chooseSingleCardFace(sa, title, predicate, name);
        List<ICardFace> faces = StaticData.instance().getCommonCards().streamAllFaces().filter(predicate).distinct().toList();
        return one(context(sa, title), faces, false, () -> faces.get(0));
    }

    @Override
    public String chooseCardName(SpellAbility sa, List<ICardFace> options, String title) {
        ICardFace face = chooseSingleCardFace(sa, options, title);
        return face == null ? "" : face.getName();
    }

    @Override
    public String chooseCardName(SpellAbility sa, Predicate<ICardFace> predicate, String valid, String title) {
        if (!usesLlm()) return super.chooseCardName(sa, predicate, valid, title);
        List<ICardFace> faces = StaticData.instance().getCommonCards().streamAllFaces().filter(predicate).filter(face -> {
                    var paper = StaticData.instance().getCommonCards().getCard(face.getName());
                    if (paper == null) return false;
                    Card card = Card.fromPaperCard(paper, player);
                    CardUtil.turnToRightFace(face.getName(), card);
                    return card.isValid(valid.split(","), sa.getHostCard().getController(), sa.getHostCard(), sa);
                }).distinct().toList();
        return chooseCardName(sa, faces, title);
    }

    @Override
    public CardState chooseSingleCardState(SpellAbility sa, List<CardState> options, String title, Map<String, Object> params) {
        return one(context(sa, title), options, false, () -> super.chooseSingleCardState(sa, options, title, params));
    }

    @Override
    public CounterType chooseCounterType(List<CounterType> options, SpellAbility sa, String title, Map<String, Object> params) {
        return one(context(sa, title), options, false, () -> super.chooseCounterType(options, sa, title, params));
    }

    @Override
    public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> options) {
        return one("Choose replacement effect order", options, false, () -> super.chooseSingleReplacementEffect(options));
    }

    @Override
    public StaticAbility chooseSingleStaticAbility(List<StaticAbility> options) {
        return one("Choose static ability", options, false, () -> super.chooseSingleStaticAbility(options));
    }

    @Override
    public String chooseProtectionType(SpellAbility sa, List<String> options) {
        return one(context(sa, "Choose protection"), options, false, () -> super.chooseProtectionType(sa, options));
    }

    @Override
    public String chooseKeywordForPump(List<String> options, SpellAbility sa, String title, Card target) {
        return one(context(sa, title), options, false, () -> super.chooseKeywordForPump(options, sa, title, target));
    }

    @Override
    public List<SpellAbility> chooseSpellAbilitiesForEffect(List<SpellAbility> options, SpellAbility sa, String title, int num, Map<String, Object> params) {
        return select(context(sa, title), options, 0, num, () -> super.chooseSpellAbilitiesForEffect(options, sa, title, num, params));
    }

    @Override
    public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> options, SpellAbility sa, String title, Map<String, Object> params) {
        return one(context(sa, title), options, true, () -> super.chooseSingleSpellForEffect(options, sa, title, params));
    }

    @Override
    public Player chooseStartingPlayer(boolean first) {
        return one("Choose starting player", getGame().getPlayers(), false, () -> super.chooseStartingPlayer(first));
    }

    @Override
    public PlayerZone chooseStartingHand(List<PlayerZone> options) {
        return one("Choose starting hand", options, false, () -> super.chooseStartingHand(options));
    }

    @Override
    public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> options) {
        return select("Choose opening-hand abilities to activate", options, 0, options.size(), () -> super.chooseSaToActivateFromOpeningHand(options));
    }

    @Override
    public Mana chooseManaFromPool(List<Mana> options) {
        return one("Choose mana from pool", options, false, () -> super.chooseManaFromPool(options));
    }

    @Override
    public void tempShowCards(Iterable<Card> cards) {
        cards.forEach(c -> temporaryCards.add(c.getId()));
    }

    private <T> T withShown(Iterable<?> options, Supplier<T> action) {
        Set<Integer> previous = new HashSet<>(temporaryCards);
        for (Object option : options) if (option instanceof Card card) temporaryCards.add(card.getId());
        try { return action.get(); }
        finally {
            temporaryCards.clear();
            temporaryCards.addAll(previous);
        }
    }

    @Override
    public void endTempShowCards() { temporaryCards.clear(); }

    @Override
    public void reveal(CardCollectionView cards, ZoneType zone, Player owner, String title, boolean suffix) {
        remember(withShown(cards, () -> title + " " + cards.stream().map(this::describe).toList()));
        super.reveal(cards, zone, owner, title, suffix);
    }

    @Override
    public void reveal(List<CardView> cards, ZoneType zone, PlayerView owner, String title, boolean suffix) {
        remember(title + " " + cards.stream().map(CardView::getName).toList());
        super.reveal(cards, zone, owner, title, suffix);
    }

    @Override
    public void notifyOfValue(SpellAbility sa, GameObject related, String value) { remember(value); }

    private void remember(String value) {
        if (notices.size() == 100) notices.remove(0);
        notices.add(value);
    }

    @Override
    public CardCollectionView cheatShuffle(CardCollectionView cards) { return cards; }

    @Override
    public Map<Card, Integer> assignCombatDamage(Card attacker, CardCollectionView blockers, CardCollectionView remaining,
                                                int amount, GameEntity defender, boolean overrideOrder) {
        return usesLlm() ? LlmCombat.damage(this, attacker, blockers, remaining, amount, defender, overrideOrder,
                () -> super.assignCombatDamage(attacker, blockers, remaining, amount, defender, overrideOrder))
                : super.assignCombatDamage(attacker, blockers, remaining, amount, defender, overrideOrder);
    }

    @Override
    public boolean chooseTargetsFor(SpellAbility sa) {
        SpellAbility step = sa instanceof WrappedAbility wrapped ? wrapped.getWrappedAbility() : sa;
        return usesLlm() ? LlmTargetSelection.choose(this, step, target -> true, null, null) : super.chooseTargetsFor(sa);
    }

    @Override
    public TargetChoices chooseNewTargetsFor(SpellAbility ability, Predicate<GameObject> filter, boolean optional) {
        if (!usesLlm()) return super.chooseNewTargetsFor(ability, filter, optional);
        SpellAbility sa = ability instanceof WrappedAbility wrapped ? wrapped.getWrappedAbility() : ability;
        if (!sa.usesTargeting()) return null;
        if (optional && !yes(context(sa, "Choose new targets? No keeps the existing targets."), () -> false)) return null;
        TargetChoices old = sa.getTargets();
        List<Integer> divisions = sa.isDividedAsYouChoose() ? old.stream().map(old::getDividedValue).toList() : null;
        return LlmTargetSelection.choose(this, sa, filter, old.size(), divisions) ? sa.getTargets() : null;
    }

    @Override
    public Pair<SpellAbilityStackInstance, GameObject> chooseTarget(SpellAbility sa, List<Pair<SpellAbilityStackInstance, GameObject>> options) {
        return one(context(sa, "Choose the target to change"), options, false, () -> super.chooseTarget(sa, options));
    }

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        if (!usesLlm()) { super.declareAttackers(attacker, combat); return; }
        LlmCombat.attack(this, attacker, combat);
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        if (!usesLlm() || !LlmCombat.block(this, defender, combat)) super.declareBlockers(defender, combat);
    }

    @Override
    public List<Card> exertAttackers(List<Card> options) {
        return select("Choose attackers to exert", options, 0, options.size(), () -> super.exertAttackers(options));
    }

    @Override
    public List<Card> enlistAttackers(List<Card> options) {
        return select("Choose attackers to enlist", options, 0, Math.min(options.size(), CostEnlist.getCardsForEnlisting(player).size()),
                () -> super.enlistAttackers(options));
    }

    @Override
    public CardCollection orderBlockers(Card attacker, CardCollection options) {
        return cards("Order blockers for " + describe(attacker), options, options.size(), options.size(), () -> super.orderBlockers(attacker, options));
    }

    @Override
    public CardCollection orderAttackers(Card blocker, CardCollection options) {
        return cards("Order attackers blocked by " + describe(blocker), options, options.size(), options.size(), () -> super.orderAttackers(blocker, options));
    }

    @Override
    public CardCollection orderBlocker(Card attacker, Card blocker, CardCollection previous) {
        CardCollection options = new CardCollection(previous);
        options.add(blocker);
        return orderBlockers(attacker, options);
    }

    @Override
    public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> options) {
        return select("Order simultaneous abilities in resolution order (first resolves first)", options,
                options.size(), options.size(), () -> super.orderSimultaneousSa(options));
    }

    @Override
    public void orderAndPlaySimultaneousSa(List<SpellAbility> abilities) {
        if (!usesLlm()) { super.orderAndPlaySimultaneousSa(abilities); return; }
        List<SpellAbility> ordered = orderSimultaneousSa(abilities);
        for (int i = ordered.size() - 1; i >= 0; i--) {
            SpellAbility sa = ordered.get(i);
            if (sa.isTrigger() && !sa.isCopied()) {
                PlaySpellAbility.playSpellAbility(this, player, sa);
            } else {
                if (sa.isCopied()) {
                    if (sa.isSpell()) {
                        if (!sa.getHostCard().isInZone(ZoneType.Stack)) {
                            sa.setHostCard(getGame().getAction().moveToStack(sa.getHostCard(), sa));
                        } else {
                            getGame().getStackZone().add(sa.getHostCard());
                        }
                    }
                    if (sa.isMayChooseNewTargets()) sa.setupNewTargets(player);
                }
                getGame().getStack().add(sa);
            }
        }
    }

    @Override
    public boolean playTrigger(Card host, WrappedAbility sa, boolean mandatory) {
        if (!usesLlm()) return super.playTrigger(host, sa, mandatory);
        return PlaySpellAbility.playSpellAbilityNoStack(this, player, sa, false);
    }

    @Override
    public void playSpellAbilityNoStack(SpellAbility sa, boolean chooseTargets) {
        if (!usesLlm()) { super.playSpellAbilityNoStack(sa, chooseTargets); return; }
        PlaySpellAbility.playSpellAbilityNoStack(this, player, sa, !chooseTargets);
    }

    @Override
    public boolean playSaFromPlayEffect(SpellAbility sa) {
        return usesLlm() ? PlaySpellAbility.playSpellAbility(this, player, sa) : super.playSaFromPlayEffect(sa);
    }

    @Override
    public CostDecisionMakerBase getCostDecisionMaker(Player payer, SpellAbility sa, boolean effect, String title) {
        return usesLlm() ? new LlmCostDecision(this, payer, sa, effect) : super.getCostDecisionMaker(payer, sa, effect, title);
    }

    @Override
    public CardCollectionView chooseCardsForCost(CardCollectionView options, SpellAbility sa, CostPartWithList cost,
                                                int count, boolean optional, String title) {
        if (!usesLlm()) {
            PaymentDecision decision = cost.accept(super.getCostDecisionMaker(player, sa, true, title));
            return decision == null ? null : decision.cards;
        }
        List<Card> chosen = select(context(sa, title), new ArrayList<>(options), optional ? 0 : count, count,
                false, list -> list.isEmpty() && optional || list.size() == count, () -> null);
        return chosen == null || chosen.isEmpty() && count > 0 ? null : new CardCollection(chosen);
    }

    @Override
    public boolean payManaCost(ManaCost cost, CostPartMana part, SpellAbility sa, String title, ManaConversionMatrix matrix, boolean effect) {
        return usesLlm() ? PlaySpellAbility.payManaCost(this, cost, part, sa, player, title, matrix, effect)
                : super.payManaCost(cost, part, sa, title, matrix, effect);
    }

    @Override
    public boolean applyManaToCost(ManaCostBeingPaid cost, SpellAbility sa, String title, ManaConversionMatrix matrix, boolean effect) {
        if (!usesLlm()) return forge.ai.ComputerUtilMana.payManaCost(cost, sa, player, effect);
        if (manaPaymentDepth >= 16) return false;
        manaPaymentDepth++;
        try { return LlmManaPayment.pay(this, cost, sa, matrix, effect); }
        finally { manaPaymentDepth--; }
    }

    @Override
    public boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean paid, FCollectionView<Player> payers) {
        if (!usesLlm()) return super.payCostToPreventEffect(cost, sa, paid, payers);
        return yes(context(sa, "Pay " + cost + " to prevent this effect?"), () -> false)
                && payInteractiveCost(cost, sa);
    }

    @Override
    public boolean payCostDuringRoll(Cost cost, SpellAbility sa) {
        return usesLlm() ? yes(context(sa, "Pay " + cost + " to modify/reroll?"), () -> false)
                && payInteractiveCost(cost, sa) : super.payCostDuringRoll(cost, sa);
    }

    @Override
    public boolean payCombatCost(Card card, Cost cost, SpellAbility sa, String title) {
        return usesLlm() ? yes(context(sa, title + " " + cost), () -> false)
                && payInteractiveCost(cost, sa) : super.payCombatCost(card, cost, sa, title);
    }

    private boolean payInteractiveCost(Cost cost, SpellAbility sa) {
        CostPayment payment = new CostPayment(cost, sa);
        boolean paid = false;
        try {
            paid = payment.payCost(getCostDecisionMaker(player, sa, true));
            return paid;
        } finally {
            if (!paid) payment.refundPayment();
        }
    }

    @Override
    public Player choosePlayerToAssistPayment(FCollectionView<Player> options, SpellAbility sa, String title, int max) {
        return one(context(sa, title), options, true, () -> super.choosePlayerToAssistPayment(options, sa, title, max));
    }

    @Override
    public boolean helpPayForAssistSpell(ManaCostBeingPaid cost, SpellAbility sa, int max, int requested) {
        if (!usesLlm()) return super.helpPayForAssistSpell(cost, sa, max, requested);
        int amount = number(context(sa, "Choose mana to contribute to assist; requested=" + requested), 0, max, () -> 0);
        if (amount == 0) return true;
        ManaCostBeingPaid contribution = new ManaCostBeingPaid(ManaCost.get(amount));
        ManaConversionMatrix matrix = new ManaConversionMatrix();
        matrix.restoreColorReplacements();
        if (!applyManaToCost(contribution, sa, "Assist payment", matrix, false)) return false;
        cost.decreaseGenericMana(amount);
        return true;
    }

    private PlannedManaColors plannedManaColors;

    private static final class PlannedManaColors {
        final int sourceId;
        final Deque<Byte> colors;
        PlannedManaColors(SpellAbility ability, List<String> choices) {
            sourceId = ability.getHostCard().getId();
            colors = new ArrayDeque<>();
            choices.forEach(choice -> colors.add(MagicColor.fromName(choice)));
        }
    }

    final boolean withManaColors(SpellAbility ability, List<String> colors, Supplier<Boolean> action) {
        final var previous = plannedManaColors;
        plannedManaColors = new PlannedManaColors(ability, colors);
        try { return action.get(); }
        finally { plannedManaColors = previous; }
    }

    private Byte plannedColor(String title, Card source, List<Byte> options) {
        if (plannedManaColors == null || source == null || plannedManaColors.sourceId != source.getId()
                || !Localizer.getInstance().getMessage("lblSelectManaProduce").equals(title)
                || plannedManaColors.colors.isEmpty()) return null;
        byte color = plannedManaColors.colors.removeFirst();
        if (options.contains(color)) return color;
        plannedManaColors.colors.clear();
        return null; // A stale/illegal preference must never bypass the engine's offered colors.
    }

    private List<Byte> colorOptions(ColorSet colors, boolean colorless) {
        List<Byte> options = new ArrayList<>();
        for (byte color : MagicColor.WUBRG) if (colors.hasAnyColor(color)) options.add(color);
        if (colorless) options.add(MagicColor.COLORLESS);
        return options;
    }

    @Override
    public byte chooseColor(String title, SpellAbility sa, ColorSet colors) {
        final Byte planned = sa == null ? null : plannedColor(title, sa.getHostCard(), colorOptions(colors, false));
        if (planned != null) return planned;
        return one(context(sa, title + " Choose color (1=white, 2=blue, 4=black, 8=red, 16=green)"), colorOptions(colors, false), false,
                () -> super.chooseColor(title, sa, colors));
    }

    @Override
    public byte chooseColorAllowColorless(String title, Card card, ColorSet colors) {
        final Byte planned = plannedColor(title, card, colorOptions(colors, true));
        if (planned != null) return planned;
        return one(title + " Choose color (0=colorless, 1=white, 2=blue, 4=black, 8=red, 16=green)", colorOptions(colors, true), false,
                () -> super.chooseColorAllowColorless(title, card, colors));
    }

    @Override
    public ColorSet chooseColors(String title, SpellAbility sa, int min, int max, ColorSet options) {
        if (!usesLlm()) return super.chooseColors(title, sa, min, max, options);
        int mask = 0;
        for (byte color : select(context(sa, title + " (1=white, 2=blue, 4=black, 8=red, 16=green)"), colorOptions(options, false), min, max, () -> List.of())) mask |= color;
        return ColorSet.fromMask(mask);
    }

    @Override
    public Map<Byte, Integer> specifyManaCombo(SpellAbility sa, ColorSet colors, int amount, boolean different) {
        if (!usesLlm()) return super.specifyManaCombo(sa, colors, amount, different);
        Map<Byte, Integer> result = new LinkedHashMap<>();
        List<Byte> options = colorOptions(colors, false);
        if (plannedManaColors != null && sa != null && plannedManaColors.sourceId == sa.getHostCard().getId()
                && !plannedManaColors.colors.isEmpty()) {
            List<Byte> proposed = plannedManaColors.colors.stream().limit(amount).toList();
            if (proposed.size() == amount && options.containsAll(proposed)
                    && (!different || new HashSet<>(proposed).size() == amount)) {
                proposed.forEach(color -> { plannedManaColors.colors.removeFirst(); result.merge(color, 1, Integer::sum); });
                return result;
            }
            plannedManaColors.colors.clear();
        }
        List<Byte> chosen = select(context(sa, "Choose " + amount + " mana colors (1=W, 2=U, 4=B, 8=R, 16=G)"), options,
                amount, amount, !different, list -> true, () -> {
                    List<Byte> fallback = new ArrayList<>();
                    for (int i = 0; i < amount; i++) fallback.add(options.get(different ? i : 0));
                    return fallback;
                });
        chosen.forEach(color -> result.merge(color, 1, Integer::sum));
        return result;
    }

    @Override
    public Map<Card, ManaCostShard> chooseCardsForConvokeOrImprovise(SpellAbility sa, ManaCost mana,
            CardCollectionView options, boolean artifacts, boolean creatures, Integer maxReduction) {
        if (!usesLlm()) return super.chooseCardsForConvokeOrImprovise(sa, mana, options, artifacts, creatures, maxReduction);
        ManaCostBeingPaid remaining = new ManaCostBeingPaid(mana);
        Map<Card, ManaCostShard> result = new LinkedHashMap<>();
        List<Card> available = new ArrayList<>(options);
        int limit = maxReduction == null ? available.size() : Math.min(maxReduction, available.size());
        while (!remaining.isPaid() && result.size() < limit) {
            available.removeIf(card -> !card.canTap() || convokeColors(card, remaining, artifacts).isEmpty());
            Card next = one(context(sa, "Choose a card for convoke/improvise or select none to finish. Remaining: " + remaining), available, true, () -> null);
            if (next == null) break;
            List<Byte> colors = convokeColors(next, remaining, artifacts);
            if (colors.isEmpty()) { available.remove(next); continue; }
            byte color = one("Choose convoke color (0=generic, 1=W, 2=U, 4=B, 8=R, 16=G)", colors, false, () -> colors.get(0));
            ManaCostShard shard = remaining.payManaViaConvoke(color);
            if (shard != null) result.put(next, shard);
            available.remove(next);
        }
        return result;
    }

    private List<Byte> convokeColors(Card card, ManaCostBeingPaid remaining, boolean artifacts) {
        List<Byte> colors = artifacts ? new ArrayList<>(List.of((byte) 0)) : colorOptions(card.getColor(), true);
        colors.removeIf(color -> new ManaCostBeingPaid(remaining).payManaViaConvoke(color) == null);
        return colors;
    }

    @Override
    public Map<GameEntity, Integer> divideShield(Card source, Map<GameEntity, Integer> options, int total) {
        if (!usesLlm()) return super.divideShield(source, options, total);
        Map<GameEntity, Integer> result = new LinkedHashMap<>();
        int remaining = total;
        List<GameEntity> targets = new ArrayList<>(options.keySet());
        for (int i = 0; i < targets.size(); i++) {
            GameEntity target = targets.get(i);
            final int max = Math.min(remaining, options.get(target));
            long laterCapacity = 0;
            for (int j = i + 1; j < targets.size(); j++) laterCapacity += options.get(targets.get(j));
            int min = (int) Math.max(0L, remaining - laterCapacity);
            int amount = number("Allocate shield to " + describe(target), min, max, () -> max);
            result.put(target, amount);
            remaining -= amount;
        }
        return result;
    }

    @Override
    public List<Card> chooseContraptionsToCrank(List<Card> options) {
        return select("Choose Contraptions to crank", options, 0, options.size(), () -> super.chooseContraptionsToCrank(options));
    }

    @Override
    public String chooseSector(Card card, String ai, List<String> options) {
        return one("Choose sector", options, false, () -> super.chooseSector(card, ai, options));
    }

    @Override
    public int chooseSprocket(Card card, List<Integer> options) {
        return one("Choose sprocket", options, false, () -> super.chooseSprocket(card, options));
    }

    @Override
    public Sticker chooseSticker(List<Sticker> options, Card target, SpellAbility sa, boolean optional) {
        return one(context(sa, "Choose sticker for " + describe(target)), options, optional, () -> super.chooseSticker(options, target, sa, optional));
    }

    @Override
    public int chooseStickerNamePosition(Sticker sticker, Card target) {
        return number("Choose number of words before sticker in " + target.getName(), 0, target.getName().split(" ").length,
                () -> super.chooseStickerNamePosition(sticker, target));
    }

    @Override
    public Card chooseCardToKeepStickers(CardCollectionView options) {
        return one("Choose card to keep stickers", options, false, () -> super.chooseCardToKeepStickers(options));
    }

    @Override
    public PlanarDice choosePDRollToIgnore(List<PlanarDice> options) {
        return one("Choose planar die result to ignore", options, false, () -> super.choosePDRollToIgnore(options));
    }

    @Override
    public Integer chooseRollToIgnore(List<Integer> options) {
        return one("Choose roll to ignore", options, false, () -> super.chooseRollToIgnore(options));
    }

    @Override
    public Integer chooseRollToModify(List<Integer> options) {
        return one("Choose roll to modify", options, false, () -> super.chooseRollToModify(options));
    }

    @Override
    public List<Integer> chooseDiceToReroll(List<Integer> options) {
        return select("Choose dice to reroll", options, 0, options.size(), () -> super.chooseDiceToReroll(options));
    }

    @Override
    public RollDiceEffect.DieRollResult chooseRollToSwap(List<RollDiceEffect.DieRollResult> options) {
        return one("Choose roll to swap", options, false, () -> super.chooseRollToSwap(options));
    }

    @Override
    public String chooseRollSwapValue(List<String> options, Integer current, int power, int toughness) {
        return one("Choose roll swap value. Current=" + current + " P/T=" + power + "/" + toughness, options, false,
                () -> super.chooseRollSwapValue(options, current, power, toughness));
    }

    @Override
    public Object vote(SpellAbility sa, String title, List<Object> options, ListMultimap<Object, Player> votes, Player voter, boolean optional) {
        return one(context(sa, title + " Current votes: " + votes), options, optional, () -> super.vote(sa, title, options, votes, voter, optional));
    }

    @Override
    public boolean chooseFlipResult(SpellAbility sa, Player flipper, boolean call) {
        List<String> options = call ? List.of("Heads", "Tails") : List.of("Win the flip", "Lose the flip");
        return one(context(sa, "Choose flip result"), options, false,
                () -> options.get(super.chooseFlipResult(sa, flipper, call) ? 0 : 1)).equals(options.get(0));
    }

    @Override
    public boolean chooseCardsPile(SpellAbility sa, CardCollectionView pile1, CardCollectionView pile2, String faceUp) {
        return yes(context(sa, "Choose pile 1 (Yes) or pile 2 (No). Pile 1: "
                + (faceUp.equals("False") ? pile1.stream().map(this::describe).toList() : pile1.size() + " hidden cards")
                + " Pile 2: " + (!faceUp.equals("True") ? pile2.stream().map(this::describe).toList() : pile2.size() + " hidden cards")),
                () -> super.chooseCardsPile(sa, pile1, pile2, faceUp));
    }
}
