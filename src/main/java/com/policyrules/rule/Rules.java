package com.policyrules.rule;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Static factories and combinators for {@link Rule}.
 *
 * <p>{@link Rule} itself carries the binary operators ({@code and}, {@code or},
 * {@code not}); this class covers the cases those cannot express: constants,
 * adapting a {@link Predicate}, and combining a whole collection of rules.</p>
 *
 * {@snippet lang = "java":
 * Rule<Customer> eligible = Rules.allOf(
 *         customer -> customer.age() >= 18,
 *         Customer::verified,
 *         Rules.not(Customer::blocked)
 * );
 *}
 */
public final class Rules {

    private static final Rule<Object> ALWAYS_TRUE = context -> true;
    private static final Rule<Object> ALWAYS_FALSE = context -> false;

    private Rules() {
    }

    /**
     * Returns a rule that always holds.
     *
     * <p>Useful as a neutral element when rules are assembled dynamically, and
     * as a stand-in in tests.</p>
     *
     * @param <T> context type
     * @return a rule matching every context
     */
    @SuppressWarnings("unchecked")
    public static <T> Rule<T> alwaysTrue() {
        return (Rule<T>) ALWAYS_TRUE;
    }

    /**
     * Returns a rule that never holds.
     *
     * @param <T> context type
     * @return a rule matching no context
     */
    @SuppressWarnings("unchecked")
    public static <T> Rule<T> alwaysFalse() {
        return (Rule<T>) ALWAYS_FALSE;
    }

    /**
     * Adapts a standard {@link Predicate} into a rule.
     *
     * @param predicate condition to wrap
     * @param <T>       context type
     * @return an equivalent rule
     * @throws NullPointerException if {@code predicate} is null
     */
    public static <T> Rule<T> of(Predicate<T> predicate) {
        Objects.requireNonNull(predicate, "predicate must not be null");
        return predicate::test;
    }

    /**
     * Returns the negation of a rule.
     *
     * <p>Equivalent to {@link Rule#not()}, but usable directly on a lambda or a
     * method reference, where the target type is not yet known.</p>
     *
     * @param rule rule to negate
     * @param <T>  context type
     * @return the negated rule
     * @throws NullPointerException if {@code rule} is null
     */
    public static <T> Rule<T> not(Rule<T> rule) {
        Objects.requireNonNull(rule, "rule must not be null");
        return rule.not();
    }

    /**
     * Returns a rule that holds when every supplied rule holds.
     *
     * <p>Evaluation short-circuits on the first rule that does not hold. An
     * empty collection yields {@link #alwaysTrue()}, the neutral element of a
     * conjunction.</p>
     *
     * @param rules rules to combine, in evaluation order
     * @param <T>   context type
     * @return the conjunction of every rule
     * @throws NullPointerException if the collection or one of its elements is null
     */
    public static <T> Rule<T> allOf(Collection<? extends Rule<T>> rules) {
        var copy = List.copyOf(Objects.requireNonNull(rules, "rules must not be null"));
        return switch (copy.size()) {
            case 0 -> alwaysTrue();
            case 1 -> copy.getFirst();
            default -> context -> {
                for (var rule : copy) {
                    if (!rule.matches(context)) {
                        return false;
                    }
                }
                return true;
            };
        };
    }

    /**
     * Returns a rule that holds when every supplied rule holds.
     *
     * @param rules rules to combine, in evaluation order
     * @param <T>   context type
     * @return the conjunction of every rule
     * @throws NullPointerException if the array or one of its elements is null
     * @see #allOf(Collection)
     */
    @SafeVarargs
    public static <T> Rule<T> allOf(Rule<T>... rules) {
        return allOf(List.of(rules));
    }

    /**
     * Returns a rule that holds when at least one supplied rule holds.
     *
     * <p>Evaluation short-circuits on the first rule that holds. An empty
     * collection yields {@link #alwaysFalse()}, the neutral element of a
     * disjunction.</p>
     *
     * @param rules rules to combine, in evaluation order
     * @param <T>   context type
     * @return the disjunction of every rule
     * @throws NullPointerException if the collection or one of its elements is null
     */
    public static <T> Rule<T> anyOf(Collection<? extends Rule<T>> rules) {
        var copy = List.copyOf(Objects.requireNonNull(rules, "rules must not be null"));
        return switch (copy.size()) {
            case 0 -> alwaysFalse();
            case 1 -> copy.getFirst();
            default -> context -> {
                for (var rule : copy) {
                    if (rule.matches(context)) {
                        return true;
                    }
                }
                return false;
            };
        };
    }

    /**
     * Returns a rule that holds when at least one supplied rule holds.
     *
     * @param rules rules to combine, in evaluation order
     * @param <T>   context type
     * @return the disjunction of every rule
     * @throws NullPointerException if the array or one of its elements is null
     * @see #anyOf(Collection)
     */
    @SafeVarargs
    public static <T> Rule<T> anyOf(Rule<T>... rules) {
        return anyOf(List.of(rules));
    }
}
