package io.github.sebas2409.policyrules.rule.trace;

import io.github.sebas2409.policyrules.rule.Rule;

import java.util.Map;
import java.util.Objects;

/**
 * An atomic rule that reports its own type and configuration in a trace.
 *
 * <p>The label and the parameters are supplied by whoever built the rule, not
 * by the rule itself, which is what lets a factory registered in a
 * {@code RuleRegistry} stay an ordinary lambda and still appear in a trace.</p>
 *
 * @param <T> type of context inspected by the rule
 */
final class AtomicExplainableRule<T> implements ExplainableRule<T> {

    /** Name reported in the trace: the registered type of the rule. */
    private final String label;

    /** Configuration the rule was built with, reported as-is. */
    private final Map<String, Object> parameters;

    /** The condition itself, unaware that it is being traced. */
    private final Rule<T> rule;

    AtomicExplainableRule(String label, Map<String, Object> parameters, Rule<T> rule) {
        this.label = ExplainableRules.requireLabel(label);
        this.parameters = Map.copyOf(
                Objects.requireNonNull(parameters, "parameters must not be null")
        );
        this.rule = Objects.requireNonNull(rule, "rule must not be null");
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public boolean matches(T context) {
        return rule.matches(context);
    }

    @Override
    public RuleTrace explain(T context) {
        return RuleTrace.leaf(
                label,
                parameters,
                rule.matches(context)
                        ? RuleTrace.Outcome.MATCHED
                        : RuleTrace.Outcome.NOT_MATCHED
        );
    }

    @Override
    public RuleTrace skipped() {
        return RuleTrace.leaf(label, parameters, RuleTrace.Outcome.SKIPPED);
    }

    @Override
    public String toString() {
        return parameters.isEmpty() ? label : label + " " + parameters;
    }
}
