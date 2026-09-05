package com.policyrules.policy;

import java.util.List;
import java.util.Objects;

/**
 * Signals that a policy result required to be allowed turned out to be denied.
 *
 * <p>Thrown by {@link PolicyResult#requireAllowed()} and
 * {@link Policy#enforce(Object)}. It exists for the boundary of an application,
 * where a denial must abort the operation and be mapped to a response; code
 * that can react to a denial should inspect {@link PolicyResult} instead, since
 * a denial is an expected outcome and not an error.</p>
 *
 * {@snippet lang = "java":
 * // In a web layer, typically translated once for the whole application:
 * try {
 *     bookingPolicy.enforce(context);
 * } catch (PolicyViolationException denied) {
 *     return ResponseEntity.unprocessableEntity().body(denied.violations());
 * }
 *}
 */
@SuppressWarnings("serial")
public final class PolicyViolationException extends RuntimeException {

    /** Immutable, non-empty reasons carried to the boundary. */
    private final List<PolicyViolation> violations;

    /**
     * Creates an exception carrying every reason for the denial.
     *
     * <p>The exception message lists the violation codes, so a stack trace is
     * useful on its own; the messages themselves stay in {@link #violations()}
     * to keep them out of logs that are not meant to hold them.</p>
     *
     * @param violations reasons why the policy denied the context
     * @throws NullPointerException     if the list or one of its elements is null
     * @throws IllegalArgumentException if the list is empty
     */
    public PolicyViolationException(List<PolicyViolation> violations) {
        super(describe(violations));
        this.violations = List.copyOf(violations);
    }

    /**
     * Returns every reason for the denial, in evaluation order.
     *
     * @return immutable, non-empty list of violations
     */
    public List<PolicyViolation> violations() {
        return violations;
    }

    /**
     * Returns the code of every violation, in evaluation order.
     *
     * @return immutable, non-empty list of violation codes
     */
    public List<String> codes() {
        return violations.stream().map(PolicyViolation::code).toList();
    }

    private static String describe(List<PolicyViolation> violations) {
        Objects.requireNonNull(violations, "violations must not be null");
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("the exception requires at least one violation");
        }
        return "Policy denied: " + violations.stream()
                .map(violation -> Objects.requireNonNull(
                        violation,
                        "violations must not contain null"
                ).code())
                .toList();
    }
}
