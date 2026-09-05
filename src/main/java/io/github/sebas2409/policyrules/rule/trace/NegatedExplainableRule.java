package io.github.sebas2409.policyrules.rule.trace;

import java.util.List;
import java.util.Objects;

/**
 * A negation that keeps the negated rule visible in the trace.
 *
 * <p>When a negation does not hold, the reason is that its child <em>did</em>
 * hold. Both facts are reported: the node is
 * {@link RuleTrace.Outcome#NOT_MATCHED} and its single child is
 * {@link RuleTrace.Outcome#MATCHED}.</p>
 *
 * @param <T> type of context inspected by the rule
 */
final class NegatedExplainableRule<T> implements ExplainableRule<T> {

    /** The rule being negated. */
    private final ExplainableRule<T> rule;

    NegatedExplainableRule(ExplainableRule<T> rule) {
        this.rule = Objects.requireNonNull(rule, "rule must not be null");
    }

    @Override
    public String label() {
        return ExplainableRules.NOT_LABEL;
    }

    @Override
    public boolean matches(T context) {
        return !rule.matches(context);
    }

    @Override
    public RuleTrace explain(T context) {
        var trace = rule.explain(context);
        return RuleTrace.composite(
                label(),
                trace.matched()
                        ? RuleTrace.Outcome.NOT_MATCHED
                        : RuleTrace.Outcome.MATCHED,
                List.of(trace)
        );
    }

    @Override
    public RuleTrace skipped() {
        return RuleTrace.composite(
                label(),
                RuleTrace.Outcome.SKIPPED,
                List.of(rule.skipped())
        );
    }

    @Override
    public String toString() {
        return label() + " " + rule;
    }
}
