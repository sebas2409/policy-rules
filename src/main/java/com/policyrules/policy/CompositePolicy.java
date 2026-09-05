package com.policyrules.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Evaluates several policies as one.
 *
 * <p>Package-private implementation behind {@link Policies#allOf} and
 * {@link Policies#firstFailureOf}. The only difference between both modes is
 * whether evaluation stops at the first denial.</p>
 *
 * @param <T> type of context evaluated by this policy
 */
final class CompositePolicy<T> implements Policy<T> {

    /** Stable identifier of the composite. */
    private final String id;

    /** Members, evaluated in declaration order. */
    private final List<? extends Policy<T>> policies;

    /** Whether evaluation stops at the first denied member. */
    private final boolean failFast;

    /**
     * Creates the composite.
     *
     * @param id       stable, non-blank identifier
     * @param policies non-empty members, in evaluation order
     * @param failFast whether to stop at the first denial
     * @throws NullPointerException     if an argument or a member is null
     * @throws IllegalArgumentException if {@code id} is blank or there are no members
     */
    CompositePolicy(String id, List<? extends Policy<T>> policies, boolean failFast) {
        this.id = Policies.requireId(id);
        this.policies = List.copyOf(
                Objects.requireNonNull(policies, "policies must not be null")
        );
        if (this.policies.isEmpty()) {
            throw new IllegalArgumentException("a composite requires at least one policy");
        }
        this.failFast = failFast;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public PolicyResult evaluate(T context) {
        var violations = new ArrayList<PolicyViolation>();

        for (var policy : policies) {
            var result = Objects.requireNonNull(
                    policy.evaluate(context),
                    "policy must not return null: " + policy.id()
            );
            if (result.denied()) {
                if (failFast) {
                    return result;
                }
                violations.addAll(result.violations());
            }
        }

        return violations.isEmpty()
                ? PolicyResult.allow()
                : PolicyResult.deny(violations);
    }

    @Override
    public String toString() {
        return "Policy[" + id + ", " + (failFast ? "first-failure" : "all") + " of "
                + policies.stream().map(Policy::id).toList() + "]";
    }
}
