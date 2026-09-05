package io.github.sebas2409.policyrules.rule.trace;

import io.github.sebas2409.policyrules.rule.Rule;

import java.util.Map;

/**
 * A {@link Rule} that can also report how it reached its answer.
 *
 * <p>An explainable rule answers the same question as any other rule, and is
 * accepted anywhere a {@code Rule} is. What it adds is {@link #explain(Object)},
 * which evaluates the rule and returns a {@link RuleTrace} describing the
 * decision node by node.</p>
 *
 * <h2>Explaining costs nothing until it is asked for</h2>
 * <p>{@link #matches(Object)} and {@link #explain(Object)} are two separate
 * evaluations of the same logic: the first allocates nothing and short-circuits
 * exactly like a plain rule, the second builds the trace. Code on a hot path
 * keeps calling {@code matches} and pays nothing for a feature it does not
 * use.</p>
 *
 * <h2>Implementation contract</h2>
 * <ul>
 *   <li>{@code matches(c)} and {@code explain(c).matched()} must agree for
 *       every context.</li>
 *   <li>Both methods must evaluate the underlying condition at most once per
 *       call, and must not evaluate a child an operator short-circuited past;
 *       those children belong in the trace as
 *       {@link RuleTrace.Outcome#SKIPPED}.</li>
 *   <li>Since the two methods evaluate independently, calling both for the same
 *       context is two evaluations. A caller that wants a trace should call
 *       {@code explain} alone and read {@link RuleTrace#matched()}, which is
 *       also the only way to be exact when a rule reads state that can change
 *       between calls.</li>
 * </ul>
 *
 * <h2>Combining</h2>
 * <p>The operators inherited from {@link Rule} return plain rules and therefore
 * lose the trace. Use {@link ExplainableRules} to combine explainable rules
 * while keeping it.</p>
 *
 * @param <T> type of context inspected by the rule
 * @see ExplainableRules
 * @see RuleTrace
 */
public interface ExplainableRule<T> extends Rule<T> {

    /**
     * Returns the name this rule reports in a trace.
     *
     * @return non-blank label, typically a registered rule type or an operator
     */
    String label();

    /**
     * Evaluates the rule and reports how it reached its answer.
     *
     * @param context business context to inspect
     * @return the trace of the evaluation, whose root outcome is the answer
     */
    RuleTrace explain(T context);

    /**
     * Returns the trace this rule reports when an operator short-circuits past
     * it, without evaluating anything.
     *
     * <p>The default is a single skipped leaf. Operators override it to keep
     * the shape of the subtree they were never asked to evaluate.</p>
     *
     * @return a trace of this rule with every outcome set to
     *         {@link RuleTrace.Outcome#SKIPPED}
     */
    default RuleTrace skipped() {
        return RuleTrace.leaf(label(), Map.of(), RuleTrace.Outcome.SKIPPED);
    }

    /**
     * Returns a rule with the inverse result of this one, keeping the trace.
     *
     * @return the negation of this rule, still explainable
     */
    @Override
    default ExplainableRule<T> not() {
        return ExplainableRules.not(this);
    }
}
