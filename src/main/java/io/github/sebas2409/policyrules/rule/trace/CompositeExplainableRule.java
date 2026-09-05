package io.github.sebas2409.policyrules.rule.trace;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A conjunction or a disjunction that records what it short-circuited past.
 *
 * <p>Evaluation stops at the first child that decides the outcome, exactly like
 * the boolean operators. The children after it were never evaluated, so they
 * are reported as {@link RuleTrace.Outcome#SKIPPED} instead of being given an
 * outcome they never produced.</p>
 *
 * @param <T> type of context inspected by the rule
 */
final class CompositeExplainableRule<T> implements ExplainableRule<T> {

    /** Children, in declaration order, which is also evaluation order. */
    private final List<? extends ExplainableRule<T>> rules;

    /** True for a conjunction, false for a disjunction. */
    private final boolean all;

    CompositeExplainableRule(List<? extends ExplainableRule<T>> rules, boolean all) {
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules must not be null"));
        if (this.rules.isEmpty()) {
            throw new IllegalArgumentException("a composite requires at least one rule");
        }
        this.all = all;
    }

    @Override
    public String label() {
        return all ? ExplainableRules.AND_LABEL : ExplainableRules.OR_LABEL;
    }

    @Override
    public boolean matches(T context) {
        for (var rule : rules) {
            if (rule.matches(context) != all) {
                return !all;
            }
        }
        return all;
    }

    @Override
    public RuleTrace explain(T context) {
        var traces = new ArrayList<RuleTrace>(rules.size());
        for (var index = 0; index < rules.size(); index++) {
            var trace = rules.get(index).explain(context);
            traces.add(trace);
            if (trace.matched() != all) {
                for (var pending : rules.subList(index + 1, rules.size())) {
                    traces.add(pending.skipped());
                }
                return RuleTrace.composite(
                        label(),
                        all ? RuleTrace.Outcome.NOT_MATCHED : RuleTrace.Outcome.MATCHED,
                        traces
                );
            }
        }
        return RuleTrace.composite(
                label(),
                all ? RuleTrace.Outcome.MATCHED : RuleTrace.Outcome.NOT_MATCHED,
                traces
        );
    }

    @Override
    public RuleTrace skipped() {
        var traces = new ArrayList<RuleTrace>(rules.size());
        for (var rule : rules) {
            traces.add(rule.skipped());
        }
        return RuleTrace.composite(label(), RuleTrace.Outcome.SKIPPED, traces);
    }

    @Override
    public String toString() {
        return label() + " " + rules;
    }
}
