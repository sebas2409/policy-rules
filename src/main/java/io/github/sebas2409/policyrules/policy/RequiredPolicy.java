package io.github.sebas2409.policyrules.policy;

import io.github.sebas2409.policyrules.rule.Rule;

import java.util.Objects;
import java.util.function.Function;

/**
 * Allows a context only when a required rule matches.
 *
 * <p>Package-private implementation behind {@link Policies#require} and
 * {@link Policies#forbid}. The violation is produced lazily, so building it can
 * be as expensive as the explanation deserves without slowing down the allowed
 * path.</p>
 *
 * @param <T> type of context evaluated by this policy
 */
final class RequiredPolicy<T> implements Policy<T> {

    /** Stable identifier of the policy. */
    private final String id;

    /** Condition that must hold for the context to be allowed. */
    private final Rule<T> rule;

    /** Builds the reason to report when the rule does not hold. */
    private final Function<T, PolicyViolation> violationFactory;

    /**
     * Creates the policy.
     *
     * @param id               stable, non-blank identifier
     * @param rule             condition that must hold
     * @param violationFactory builds the violation for a denied context
     * @throws NullPointerException     if an argument is null
     * @throws IllegalArgumentException if {@code id} is blank
     */
    RequiredPolicy(String id, Rule<T> rule, Function<T, PolicyViolation> violationFactory) {
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
        if (rule.matches(context)) {
            return PolicyResult.allow();
        }
        return PolicyResult.deny(Objects.requireNonNull(
                violationFactory.apply(context),
                "violationFactory must not return null: " + id
        ));
    }

    @Override
    public String toString() {
        return "Policy[" + id + "]";
    }
}
