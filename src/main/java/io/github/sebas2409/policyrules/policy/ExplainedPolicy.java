package io.github.sebas2409.policyrules.policy;

import io.github.sebas2409.policyrules.rule.trace.ExplainableRule;

import java.util.Objects;
import java.util.function.Function;

/**
 * A policy that attaches the trace of its rule to the violation it reports.
 *
 * <p>The rule is evaluated exactly once, through
 * {@link ExplainableRule#explain(Object)}, and the answer is read from the
 * resulting trace. Evaluating once is not only cheaper: it is the only way for
 * the reported explanation to describe the very evaluation that produced the
 * denial, rather than a second one that could disagree.</p>
 *
 * @param <T> context type
 */
final class ExplainedPolicy<T> implements Policy<T> {

    /** Stable identifier of the policy. */
    private final String id;

    /** Condition that must hold, able to report how it decided. */
    private final ExplainableRule<T> rule;

    /** Builds the violation reported when the rule does not hold. */
    private final Function<T, PolicyViolation> violationFactory;

    ExplainedPolicy(
            String id,
            ExplainableRule<T> rule,
            Function<T, PolicyViolation> violationFactory
    ) {
        this.id = Policies.requireId(id);
        this.rule = Objects.requireNonNull(rule, "rule must not be null");
        this.violationFactory = Objects.requireNonNull(
                violationFactory,
                "violationFactory must not be null"
        );
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public PolicyResult evaluate(T context) {
        var trace = rule.explain(context);
        if (trace.matched()) {
            return PolicyResult.allow();
        }

        var violation = Objects.requireNonNull(
                violationFactory.apply(context),
                "violationFactory must not return null: " + id
        ).with(Policies.TRACE_METADATA_KEY, trace);

        return PolicyResult.deny(trace.culprit()
                .map(culprit -> violation.with(Policies.CULPRIT_METADATA_KEY, culprit))
                .orElse(violation));
    }

    @Override
    public String toString() {
        return "Policy[" + id + ", explained]";
    }
}
