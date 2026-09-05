package io.github.sebas2409.policyrules.rule.trace;

import io.github.sebas2409.policyrules.rule.Rule;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Factories that turn plain rules into rules that can explain themselves.
 *
 * <p>The operators mirror the ones of
 * {@code io.github.sebas2409.policyrules.rule.definition.RuleDefinitions}, so a trace has the
 * same shape as the definition that produced it and can be laid over the
 * document a person edited.</p>
 *
 * {@snippet lang = "java":
 * ExplainableRule<Customer> adult =
 *         ExplainableRules.of("adult", customer -> customer.age() >= 18);
 * ExplainableRule<Customer> verified =
 *         ExplainableRules.of("verified", Customer::verified);
 *
 * ExplainableRule<Customer> eligible = ExplainableRules.and(adult, verified);
 *
 * RuleTrace trace = eligible.explain(customer);
 *}
 *
 * <p>Rules built from configuration do not need any of this: a
 * {@code RuleCompiler} labels every node from the definition it is compiling,
 * so a registered factory stays an ordinary lambda.</p>
 *
 * @see ExplainableRule
 * @see RuleTrace
 */
public final class ExplainableRules {

    /** Label reported by a conjunction. */
    public static final String AND_LABEL = "and";

    /** Label reported by a disjunction. */
    public static final String OR_LABEL = "or";

    /** Label reported by a negation. */
    public static final String NOT_LABEL = "not";

    private ExplainableRules() {
    }

    /**
     * Gives a rule a name, so that it appears in a trace.
     *
     * @param label name to report; typically the registered type of the rule
     * @param rule  condition to wrap
     * @param <T>   context type of the rule
     * @return an explainable rule that delegates to {@code rule}
     * @throws NullPointerException     if an argument is null
     * @throws IllegalArgumentException if {@code label} is blank
     */
    public static <T> ExplainableRule<T> of(String label, Rule<T> rule) {
        return new AtomicExplainableRule<>(label, Map.of(), rule);
    }

    /**
     * Gives a rule a name and the configuration it was built with.
     *
     * <p>The parameters are reported verbatim in the trace, which is what lets
     * a reader see that a limit was exceeded and by which limit, without
     * looking the rule up in the store.</p>
     *
     * @param label      name to report; typically the registered type
     * @param parameters configuration the rule was built with
     * @param rule       condition to wrap
     * @param <T>        context type of the rule
     * @return an explainable rule that delegates to {@code rule}
     * @throws NullPointerException     if an argument is null
     * @throws IllegalArgumentException if {@code label} is blank
     */
    public static <T> ExplainableRule<T> of(
            String label,
            Map<String, Object> parameters,
            Rule<T> rule
    ) {
        return new AtomicExplainableRule<>(label, parameters, rule);
    }

    /**
     * Combines rules with a conjunction that reports what it skipped.
     *
     * @param rules operands, in evaluation order
     * @param <T>   context type of the rules
     * @return a rule that holds when every operand holds
     * @throws NullPointerException     if the list or an element is null
     * @throws IllegalArgumentException if the list is empty
     */
    public static <T> ExplainableRule<T> and(List<? extends ExplainableRule<T>> rules) {
        return new CompositeExplainableRule<>(rules, true);
    }

    /**
     * Combines rules with a conjunction that reports what it skipped.
     *
     * @param rules operands, in evaluation order
     * @param <T>   context type of the rules
     * @return a rule that holds when every operand holds
     * @throws NullPointerException     if the array or an element is null
     * @throws IllegalArgumentException if the array is empty
     */
    @SafeVarargs
    public static <T> ExplainableRule<T> and(ExplainableRule<T>... rules) {
        return and(List.of(Objects.requireNonNull(rules, "rules must not be null")));
    }

    /**
     * Combines rules with a disjunction that reports what it skipped.
     *
     * @param rules operands, in evaluation order
     * @param <T>   context type of the rules
     * @return a rule that holds when any operand holds
     * @throws NullPointerException     if the list or an element is null
     * @throws IllegalArgumentException if the list is empty
     */
    public static <T> ExplainableRule<T> or(List<? extends ExplainableRule<T>> rules) {
        return new CompositeExplainableRule<>(rules, false);
    }

    /**
     * Combines rules with a disjunction that reports what it skipped.
     *
     * @param rules operands, in evaluation order
     * @param <T>   context type of the rules
     * @return a rule that holds when any operand holds
     * @throws NullPointerException     if the array or an element is null
     * @throws IllegalArgumentException if the array is empty
     */
    @SafeVarargs
    public static <T> ExplainableRule<T> or(ExplainableRule<T>... rules) {
        return or(List.of(Objects.requireNonNull(rules, "rules must not be null")));
    }

    /**
     * Negates a rule, keeping the negated rule visible in the trace.
     *
     * @param rule rule to negate
     * @param <T>  context type of the rule
     * @return the negation of {@code rule}
     * @throws NullPointerException if {@code rule} is null
     */
    public static <T> ExplainableRule<T> not(ExplainableRule<T> rule) {
        return new NegatedExplainableRule<>(rule);
    }

    static String requireLabel(String label) {
        Objects.requireNonNull(label, "label must not be null");
        if (label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        return label;
    }
}
