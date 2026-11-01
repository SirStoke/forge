package forge.ai.llm;

import forge.card.CardType;
import forge.card.ColorSet;
import forge.game.GameEntityCounterTable;
import forge.game.ability.AbilityUtils;
import forge.game.card.*;
import forge.game.cost.*;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.Expressions;
import forge.util.MyRandom;

import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rules-based cost options with model-owned selections; never asks AI heuristics to prepare payments. */
final class LlmCostDecision extends CostDecisionMakerBase {
    private final LlmDecisionController controller;

    LlmCostDecision(LlmDecisionController controller, Player player, SpellAbility ability, boolean effect) {
        super(player, effect, ability, ability.getHostCard());
        this.controller = controller;
    }

    @Override public boolean paysRightAfterDecision() { return true; }

    private int amount(CostPart cost) {
        // A trailing '+' denotes a minimum count (e.g. exile 1+ cards with a total mana value).
        return AbilityUtils.calculateAmount(source, cost.getAmount().split("[+]")[0], ability);
    }

    private boolean approve(CostPart cost) {
        return cost.canPay(ability, player, isEffect())
                && controller.confirmPayment(cost, "Pay this cost?", ability);
    }

    private PaymentDecision fixed(CostPart cost) {
        return approve(cost) ? PaymentDecision.number(amount(cost)) : null;
    }

    private CardCollection valid(Iterable<Card> pool, String type) {
        return CardLists.getValidCards(new CardCollection(pool), type.split(";"), player, source, ability);
    }

    private PaymentDecision choose(CostPart cost, CardCollectionView options, int min, int max, Predicate<List<Card>> legal) {
        if (options.size() < min) return null;
        List<Card> selected = controller.select(controller.context(ability, "Choose " + min + ".." + max + " cards to pay: " + cost
                        + ". Select no options to cancel payment."),
                new ArrayList<>(options), 0, max, false,
                chosen -> chosen.isEmpty() || chosen.size() >= min && legal.test(chosen), () -> null);
        return selected == null || selected.isEmpty() && (min > 0 || !legal.test(selected)) ? null : PaymentDecision.card(selected);
    }

    private PaymentDecision choose(CostPart cost, CardCollectionView options) {
        int count = "All".equals(cost.getAmount()) ? options.size() : amount(cost);
        return choose(cost, options, count, count, list -> true);
    }

    private CardCollection sourceOrValid(CostPart cost, Iterable<Card> pool) {
        CardCollection cards = new CardCollection(pool);
        if (cost.payCostFromSource()) return cards.contains(source) ? new CardCollection(source) : new CardCollection();
        if ("OriginalHost".equals(cost.getType())) {
            Card original = ability.getOriginalHost();
            return cards.contains(original) ? new CardCollection(original) : new CardCollection();
        }
        return valid(cards, cost.getType());
    }

    private PaymentDecision oneSourceOrChoose(CostPart cost, CardCollectionView options) {
        return cost.payCostFromSource() || "OriginalHost".equals(cost.getType())
                ? choose(cost, options, 1, 1, list -> true) : choose(cost, options);
    }

    @Override public PaymentDecision visit(CostPartMana cost) { return PaymentDecision.number(0); }
    @Override public PaymentDecision visit(CostAddMana cost) { return PaymentDecision.number(amount(cost)); }
    @Override public PaymentDecision visit(CostTap cost) { return cost.canPay(ability, player, isEffect()) ? PaymentDecision.number(1) : null; }
    @Override public PaymentDecision visit(CostUntap cost) { return cost.canPay(ability, player, isEffect()) ? PaymentDecision.number(1) : null; }
    @Override public PaymentDecision visit(CostDamage cost) { return fixed(cost); }
    @Override public PaymentDecision visit(CostMill cost) { return fixed(cost); }
    @Override public PaymentDecision visit(CostPayLife cost) { return fixed(cost); }
    @Override public PaymentDecision visit(CostPayEnergy cost) { return fixed(cost); }
    @Override public PaymentDecision visit(CostPayShards cost) { return fixed(cost); }
    @Override public PaymentDecision visit(CostFlipCoin cost) { return fixed(cost); }
    @Override public PaymentDecision visit(CostRollDice cost) { return fixed(cost); }
    @Override public PaymentDecision visit(CostPutCounterYou cost) { return fixed(cost); }
    @Override public PaymentDecision visit(CostRevealChosen cost) { return approve(cost) ? PaymentDecision.number(1) : null; }

    @Override
    public PaymentDecision visit(CostChooseColor cost) {
        int count = amount(cost);
        return PaymentDecision.colors(controller.chooseColors("Choose colors for cost", ability, count, count, ColorSet.WUBRG));
    }

    @Override
    public PaymentDecision visit(CostChooseCreatureType cost) {
        String type = controller.chooseSomeType("Creature", ability, CardType.getAllCreatureTypes(), false);
        return type == null ? null : PaymentDecision.type(type);
    }

    @Override
    public PaymentDecision visit(CostDiscard cost) {
        CardCollection hand = new CardCollection(player.getCardsIn(ZoneType.Hand));
        String type = cost.getType();
        if (cost.payCostFromSource()) return hand.contains(source) ? PaymentDecision.card(source) : null;
        if (type.equals("Hand")) return approve(cost) ? PaymentDecision.card(controller.orderMoveToZoneList(hand, ZoneType.Graveyard, ability)) : null;
        if (type.equals("LastDrawn")) return hand.contains(player.getLastDrawnCard()) ? PaymentDecision.card(player.getLastDrawnCard()) : null;
        int count = amount(cost);
        if (type.equals("Random")) {
            if (hand.size() < count || !approve(cost)) return null;
            Collections.shuffle(hand, MyRandom.getRandom());
            return PaymentDecision.card(hand.subList(0, count));
        }
        boolean different = type.contains("+WithDifferentNames");
        boolean same = type.contains("+WithSameName");
        hand = valid(hand, type.replace("+WithDifferentNames", "").replace("+WithSameName", ""));
        return choose(cost, hand, count, count, cards -> (!different || cards.stream().map(Card::getName).distinct().count() == cards.size())
                && (!same || cards.stream().map(Card::getName).distinct().count() <= 1));
    }

    @Override
    public PaymentDecision visit(CostSacrifice cost) {
        String type = cost.getType().replace("+WithDifferentNames", "");
        CardCollection available = CardLists.filter(player.getCardsIn(ZoneType.Battlefield), CardPredicates.canBeSacrificedBy(ability, isEffect()));
        if (cost.payCostFromSource() || "OriginalHost".equals(type)) return oneSourceOrChoose(cost, sourceOrValid(cost, available));
        available = valid(available, type);
        int count = "All".equals(cost.getAmount()) ? available.size() : amount(cost);
        return choose(cost, available, count, count, cards -> !cost.getType().contains("+WithDifferentNames")
                || cards.stream().map(Card::getName).distinct().count() == cards.size());
    }

    @Override
    public PaymentDecision visit(CostReturn cost) {
        return oneSourceOrChoose(cost, sourceOrValid(cost, player.getCardsIn(ZoneType.Battlefield)));
    }

    @Override
    public PaymentDecision visit(CostTapType cost) {
        String type = cost.getType();
        boolean sameType = type.contains(".sharesCreatureTypeWith");
        Matcher power = Pattern.compile("\\+withTotalPowerGE(.+)$").matcher(type);
        String requiredPower = power.find() ? power.group(1) : null;
        type = type.replace(".sharesCreatureTypeWith", "").replaceAll("\\+withTotalPowerGE.+$", "");
        CardCollection cards = "OriginalHost".equals(type) ? new CardCollection(ability.getOriginalHost())
                : valid(player.getCardsIn(ZoneType.Battlefield), type);
        cards = CardLists.filter(cards, ability.isCrew() ? CardPredicates.CAN_CREW : CardPredicates.CAN_TAP);
        if (!cost.canTapSource) cards.remove(source);
        int count = "Any".equals(cost.getAmount()) || requiredPower != null ? 0 : amount(cost);
        int max = "Any".equals(cost.getAmount()) || requiredPower != null ? cards.size() : count;
        final int total = requiredPower == null ? 0 : AbilityUtils.calculateAmount(source, requiredPower, ability);
        return choose(cost, cards, count, max, chosen -> (!sameType || chosen.stream().allMatch(c -> c == chosen.get(0) || c.sharesCreatureTypeWith(chosen.get(0))))
                && (requiredPower == null || CardLists.getTotalPower(new CardCollection(chosen), ability) >= total));
    }

    @Override
    public PaymentDecision visit(CostUntapType cost) {
        CardCollection cards = valid(player.getGame().getCardsIn(ZoneType.Battlefield), cost.getType());
        cards = CardLists.filter(cards, c -> c.canUntap(null, false)
                && (c.getCounters(CounterEnumType.STUN) == 0 || c.canRemoveCounters(CounterEnumType.STUN)));
        return choose(cost, cards);
    }

    @Override
    public PaymentDecision visit(CostExile cost) {
        CardCollection pool = new CardCollection(cost.zoneRestriction == 1 ? player.getCardsIn(cost.from) : player.getGame().getCardsIn(cost.from));
        pool = CardLists.filter(pool, CardPredicates.canExiledBy(ability, isEffect()));
        if (cost.payCostFromSource() || "OriginalHost".equals(cost.getType())) return oneSourceOrChoose(cost, sourceOrValid(cost, pool));
        if ("All".equals(cost.getType())) return approve(cost) ? PaymentDecision.card(pool) : null;
        if (cost.from.size() == 1 && cost.from.contains(ZoneType.Library)) {
            int count = amount(cost);
            CardCollection top = new CardCollection(player.getCardsIn(ZoneType.Library).subList(0, Math.min(count, player.getCardsIn(ZoneType.Library).size())));
            return top.size() == count && top.stream().allMatch(c -> c.canExiledBy(ability, isEffect())) && approve(cost)
                    ? PaymentDecision.card(top) : null;
        }
        String type = cost.getType();
        boolean topGrave = type.contains("FromTopGrave");
        type = type.replace("FromTopGrave", "");
        Matcher cmc = Pattern.compile("\\+withTotalCMC(EQ|GE)([^+]+)").matcher(type);
        String comparison = cmc.find() ? cmc.group(1) : null;
        int total = comparison == null ? 0 : AbilityUtils.calculateAmount(source, cmc.group(2), ability);
        Matcher types = Pattern.compile("\\+withTypesGE([^+]+)").matcher(type);
        int typeCount = types.find() ? AbilityUtils.calculateAmount(source, types.group(1), ability) : -1;
        boolean sharedType = type.contains("+withSharedCardType");
        Matcher symbols = Pattern.compile("\\+withTotalManaSymbols_([^_]+)_([^+]+)").matcher(type);
        String symbolColor = symbols.find() ? symbols.group(1) : null;
        String symbolComparison = symbolColor == null ? null : symbols.group(2);
        type = type.replaceAll("\\+withTotalCMC(EQ|GE)[^+]+", "").replaceAll("\\+withTypesGE[^+]+", "")
                .replace("+withSharedCardType", "").replaceAll("\\+withTotalManaSymbols_[^_]+_[^+]+", "");
        pool = valid(pool, type);
        int count = amount(cost);
        if (topGrave) {
            Collections.reverse(pool);
            return pool.size() >= count ? PaymentDecision.card(pool.subList(0, count)) : null;
        }
        final String cmp = comparison;
        int max = comparison != null || typeCount >= 0 || symbolColor != null ? pool.size() : count;
        int min = typeCount >= 0 ? 1 : count;
        return choose(cost, pool, min, max, cards -> {
            CardCollection selected = new CardCollection(cards);
            if (cost.zoneRestriction == 0 && cards.stream().map(Card::getOwner).distinct().count() > 1) return false;
            if (cmp != null && !Expressions.compare(CardLists.getTotalCMC(selected), cmp, total)) return false;
            if (typeCount >= 0 && AbilityUtils.countCardTypesFromList(selected, false) < typeCount) return false;
            if (sharedType && !cards.isEmpty() && cards.stream().anyMatch(c -> !c.sharesCardTypeWith(cards.get(0)))) return false;
            return symbolColor == null || Expressions.compare(CardLists.getTotalChroma(selected, forge.card.MagicColor.fromName(symbolColor)),
                    symbolComparison, AbilityUtils.calculateAmount(source, symbolComparison.substring(2), ability));
        });
    }

    @Override
    public PaymentDecision visit(CostCollectEvidence cost) {
        CardCollection cards = CardLists.filter(player.getCardsIn(ZoneType.Graveyard), CardPredicates.canExiledBy(ability, isEffect()));
        int total = amount(cost);
        return choose(cost, cards, 0, cards.size(), chosen -> CardLists.getTotalCMC(new CardCollection(chosen)) >= total);
    }

    @Override
    public PaymentDecision visit(CostForage cost) {
        CardCollection food = CardLists.filter(player.getCardsIn(ZoneType.Battlefield), CardPredicates.isType("Food"), CardPredicates.canBeSacrificedBy(ability, isEffect()));
        CardCollection grave = CardLists.filter(player.getCardsIn(ZoneType.Graveyard), CardPredicates.canExiledBy(ability, isEffect()));
        List<String> options = new ArrayList<>();
        if (!food.isEmpty()) options.add("Sacrifice a Food");
        if (grave.size() >= 3) options.add("Exile three cards from graveyard");
        String choice = controller.one(controller.context(ability, "Choose how to forage"), options, true, () -> null);
        return choice == null ? null : choice.equals("Sacrifice a Food") ? choose(cost, food, 1, 1, list -> true) : choose(cost, grave, 3, 3, list -> true);
    }

    @Override
    public PaymentDecision visit(CostExileFromStack cost) {
        List<SpellAbility> options = new ArrayList<>();
        for (var entry : player.getGame().getStack()) {
            SpellAbility sa = entry.getSpellAbility().getRootAbility();
            if (sa.isSpell() && ("All".equals(cost.getType()) || sa.getHostCard().isValid(cost.getType().split(";"), player, source, ability))) options.add(sa);
        }
        int count = "All".equals(cost.getType()) ? options.size() : amount(cost);
        if (options.size() < count) return null;
        List<SpellAbility> chosen = controller.select(controller.context(ability, "Exile spells from stack to pay " + cost), options, count, count, () -> null);
        return chosen == null ? null : PaymentDecision.spellabilities(chosen);
    }

    @Override
    public PaymentDecision visit(CostExiledMoveToGrave cost) {
        return choose(cost, valid(player.getGame().getCardsIn(ZoneType.Exile), cost.getType()));
    }

    @Override
    public PaymentDecision visit(CostExert cost) {
        return oneSourceOrChoose(cost, sourceOrValid(cost, player.getCardsIn(ZoneType.Battlefield)));
    }

    @Override
    public PaymentDecision visit(CostEnlist cost) {
        return choose(cost, CostEnlist.getCardsForEnlisting(player), 1, 1, list -> true);
    }

    @Override
    public PaymentDecision visit(CostGainControl cost) {
        return choose(cost, CardLists.filter(sourceOrValid(cost, player.getGame().getCardsIn(ZoneType.Battlefield)), c -> c.canBeControlledBy(player)));
    }

    @Override
    public PaymentDecision visit(CostDraw cost) {
        if (!approve(cost)) return null;
        PaymentDecision decision = PaymentDecision.players(cost.getPotentialPlayers(player, ability));
        decision.c = amount(cost);
        return decision;
    }

    @Override
    public PaymentDecision visit(CostGainLife cost) {
        List<Player> options = cost.getPotentialTargets(player, ability).stream().filter(Player::canGainLife).toList();
        int count = cost.getCntPlayers() == Integer.MAX_VALUE ? options.size() : cost.getCntPlayers();
        if (options.size() < count) return null;
        List<Player> chosen = controller.select(controller.context(ability, "Choose players to pay " + cost), options, count, count, () -> null);
        return chosen == null ? null : PaymentDecision.players(chosen);
    }

    @Override
    public PaymentDecision visit(CostPromiseGift cost) {
        Player chosen = controller.one(controller.context(ability, "Choose opponent to promise a gift"), cost.getPotentialPlayers(player, ability), false, () -> null);
        return chosen == null ? null : PaymentDecision.players(List.of(chosen));
    }

    @Override
    public PaymentDecision visit(CostPutCardToLib cost) {
        CardCollection cards = sourceOrValid(cost, cost.sameZone ? player.getGame().getCardsIn(cost.from) : player.getCardsIn(cost.from));
        int count = cost.payCostFromSource() ? 1 : amount(cost);
        return choose(cost, cards, count, count, chosen -> !cost.sameZone || chosen.stream().map(Card::getOwner).distinct().count() <= 1);
    }

    @Override
    public PaymentDecision visit(CostPutCounter cost) {
        CardCollection options = CardLists.filter(sourceOrValid(cost, player.getGame().getCardsIn(ZoneType.Battlefield)), CardPredicates.canReceiveCounters(cost.getCounter()));
        return choose(cost, options, 1, 1, list -> true);
    }

    @Override public PaymentDecision visit(CostBlight cost) { return visit((CostPutCounter) cost); }

    @Override
    public PaymentDecision visit(CostReveal cost) {
        CardCollection options = new CardCollection(player.getCardsIn(cost.getRevealFrom()));
        if (cost.payCostFromSource()) return options.contains(source) ? PaymentDecision.card(source) : null;
        if ("Hand".equals(cost.getType())) return approve(cost) ? PaymentDecision.card(options) : null;
        boolean sameColor = "SameColor".equals(cost.getType());
        if (!sameColor) options = valid(options, cost.getType());
        int count = amount(cost);
        return choose(cost, options, count, count, cards -> !sameColor || cards.isEmpty() || cards.stream().allMatch(c -> c == cards.get(0) || c.sharesColorWith(cards.get(0))));
    }

    @Override public PaymentDecision visit(CostBehold cost) { return choose(cost, valid(player.getCardsIn(cost.getRevealFrom()), cost.getType())); }
    @Override public PaymentDecision visit(CostBeholdExile cost) { return choose(cost, valid(player.getCardsIn(cost.getRevealFrom()), cost.getType())); }

    private record CounterChoice(Card card, CounterType type, String label) {
        @Override public String toString() { return label; }
    }

    private PaymentDecision counters(CostPart cost, CardCollectionView cards, CounterType type, int count, boolean oneCard) {
        if (count == 0) return PaymentDecision.counters(new GameEntityCounterTable());
        List<CounterChoice> options = new ArrayList<>();
        for (Card card : cards) {
            for (CounterType counter : card.getCounters().elementSet()) {
                if ((type == null || type.equals(counter)) && card.canRemoveCounters(counter)) {
                    options.add(new CounterChoice(card, counter, controller.describe(card) + " remove " + counter));
                }
            }
        }
        final int needed = count;
        if (options.isEmpty() || count < 0) return null;
        List<CounterChoice> selected = controller.select(controller.context(ability, "Choose one option per counter to remove for " + cost
                        + ". Repeated indices remove multiple counters of that type."), options, count, count, true,
                chosen -> {
                    if (oneCard && chosen.stream().map(CounterChoice::card).distinct().count() > 1) return false;
                    Map<CounterChoice, Integer> counts = new HashMap<>();
                    chosen.forEach(c -> counts.merge(c, 1, Integer::sum));
                    return counts.entrySet().stream().allMatch(e -> e.getValue() <= e.getKey().card().getCounters(e.getKey().type()));
                }, () -> null);
        if (selected == null || selected.size() != needed) return null;
        GameEntityCounterTable table = new GameEntityCounterTable();
        selected.forEach(c -> table.put(null, c.card(), c.type(), 1));
        return PaymentDecision.counters(table);
    }

    @Override
    public PaymentDecision visit(CostRemoveAnyCounter cost) {
        CardCollection cards = sourceOrValid(cost, player.getCardsIn(ZoneType.Battlefield));
        return counters(cost, cards, cost.counter, amount(cost), false);
    }

    @Override
    public PaymentDecision visit(CostRemoveCounter cost) {
        CardCollection options = sourceOrValid(cost, player.getCardsIn(cost.zone));
        int count = "All".equals(cost.getAmount()) ? 0 : amount(cost);
        if ("All".equals(cost.getAmount()) || count < 0) {
            Card card = controller.one(controller.context(ability, "Choose card to remove all counters for " + cost), options, false, () -> null);
            if (card == null) return null;
            count = cost.counter == null ? card.getNumAllCounters() : card.getCounters(cost.counter);
            options = new CardCollection(card);
        }
        return counters(cost, options, cost.counter, count, true);
    }

    @Override
    public PaymentDecision visit(CostUnattach cost) {
        return choose(cost, cost.findCardToUnattach(source, player, ability), 1, 1, list -> true);
    }
}
