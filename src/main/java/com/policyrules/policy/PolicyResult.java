package com.policyrules.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The immutable outcome of evaluating one or more policies.
 *
 * <p>A result is a list of violations and nothing else: an empty list means
 * allowed, a non-empty list means denied. Deriving {@link #allowed()} from the
 * violations rather than storing it makes the contradictory states
 * ("denied without a reason", "allowed with violations") impossible to
 * build.</p>
 *
 * {@snippet lang = "java":
 * PolicyResult result = bookingPolicy.evaluate(context);
 *
 * if (result.allowed()) {
 *     bookings.save(booking);
 * } else {
 *     return badRequest(result.violations());
 * }
 *}
 *
 * <p>Results compose with {@link #combine(PolicyResult)}, which is what the
 * composite policies of {@link Policies} use to accumulate reasons across
 * several policies.</p>
 *
 * @param violations immutable reasons for a denial, empty when allowed
 * @see PolicyViolation
 */
public record PolicyResult(
        List<PolicyViolation> violations
) {

    private static final PolicyResult ALLOWED = new PolicyResult(List.of());

    /**
     * Creates a result and defensively copies its violations.
     *
     * @throws NullPointerException if the list or one of its elements is null
     */
    public PolicyResult {
        violations = List.copyOf(
                Objects.requireNonNull(violations, "violations must not be null")
        );
    }

    /**
     * Returns the shared allowed result.
     *
     * @return a result without violations
     */
    public static PolicyResult allow() {
        return ALLOWED;
    }

    /**
     * Creates a denied result with a single reason.
     *
     * @param violation reason for the denial
     * @return a denied result
     * @throws NullPointerException if {@code violation} is null
     */
    public static PolicyResult deny(PolicyViolation violation) {
        return new PolicyResult(List.of(
                Objects.requireNonNull(violation, "violation must not be null")
        ));
    }

    /**
     * Creates a denied result with a single reason.
     *
     * @param code    stable, machine-readable identifier of the reason
     * @param message human-readable explanation
     * @return a denied result
     * @throws NullPointerException     if an argument is null
     * @throws IllegalArgumentException if an argument is blank
     */
    public static PolicyResult deny(String code, String message) {
        return deny(new PolicyViolation(code, message));
    }

    /**
     * Creates a denied result with every supplied reason.
     *
     * @param violations reasons for the denial; must not be empty
     * @return a denied result
     * @throws NullPointerException     if the list or one of its elements is null
     * @throws IllegalArgumentException if the list is empty
     */
    public static PolicyResult deny(List<PolicyViolation> violations) {
        var copy = List.copyOf(
                Objects.requireNonNull(violations, "violations must not be null")
        );
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("a denied result requires at least one violation");
        }
        return new PolicyResult(copy);
    }

    /**
     * Indicates whether the context satisfied every policy.
     *
     * @return {@code true} when there are no violations
     */
    public boolean allowed() {
        return violations.isEmpty();
    }

    /**
     * Indicates whether the context was rejected.
     *
     * @return {@code true} when there is at least one violation
     */
    public boolean denied() {
        return !allowed();
    }

    /**
     * Returns the code of every violation, in evaluation order.
     *
     * @return immutable list of violation codes, empty when allowed
     */
    public List<String> codes() {
        return violations.stream().map(PolicyViolation::code).toList();
    }

    /**
     * Indicates whether a specific reason is present.
     *
     * <p>Lets a caller react to one known denial without depending on the order
     * in which policies were evaluated.</p>
     *
     * @param code violation code to look for
     * @return {@code true} when a violation with that code is present
     * @throws NullPointerException if {@code code} is null
     */
    public boolean hasViolation(String code) {
        Objects.requireNonNull(code, "code must not be null");
        return violations.stream().anyMatch(violation -> violation.code().equals(code));
    }

    /**
     * Returns the first reason for the denial.
     *
     * @return the first violation, or an empty optional when allowed
     */
    public Optional<PolicyViolation> firstViolation() {
        return violations.isEmpty() ? Optional.empty() : Optional.of(violations.getFirst());
    }

    /**
     * Combines this result with another, preserving violation order.
     *
     * @param other result to append
     * @return an allowed result when both are allowed, otherwise a denied result
     * carrying the violations of both
     * @throws NullPointerException if {@code other} is null
     */
    public PolicyResult combine(PolicyResult other) {
        Objects.requireNonNull(other, "other must not be null");
        if (allowed()) {
            return other;
        }
        if (other.allowed()) {
            return this;
        }

        var combined = new ArrayList<PolicyViolation>(
                violations.size() + other.violations.size()
        );
        combined.addAll(violations);
        combined.addAll(other.violations);
        return deny(combined);
    }

    /**
     * Fails when this result is denied.
     *
     * <p>Intended for the boundary of an application, where a denial must abort
     * the operation. Code that can handle a denial should branch on
     * {@link #allowed()} instead.</p>
     *
     * @throws PolicyViolationException carrying every violation, when denied
     */
    public void requireAllowed() {
        if (denied()) {
            throw new PolicyViolationException(violations);
        }
    }
}
