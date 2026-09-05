package io.github.sebas2409.policyrules.policy;

import java.util.Objects;

/**
 * A named business decision over a context, able to explain a denial.
 *
 * <p>A policy answers "may this happen?" and, when the answer is no, says why.
 * That is the difference with a {@link io.github.sebas2409.policyrules.rule.Rule}, which only
 * answers yes or no: a rule is the condition, a policy is the condition plus the
 * meaning of failing it.</p>
 *
 * {@snippet lang = "java":
 * Policy<Booking> mustBeActive = Policies.require(
 *         "booking-must-be-active",
 *         Booking::active,
 *         "BOOKING_INACTIVE",
 *         "The booking must be active"
 * );
 *
 * PolicyResult result = mustBeActive.evaluate(booking);
 *}
 *
 * <p>Policies are built with the factories in {@link Policies} and combined with
 * {@link Policies#allOf(String, java.util.List)} or
 * {@link Policies#firstFailureOf(String, java.util.List)}. A composite is itself
 * a policy, so an application can expose one policy per use case regardless of
 * how many conditions it is made of.</p>
 *
 * <h2>Implementation contract</h2>
 * <ul>
 *   <li>{@link #id()} must be stable and non-blank; it identifies the policy in
 *       logs, metrics and tests.</li>
 *   <li>{@link #evaluate(Object)} must never return {@code null} and must be
 *       free of observable side effects.</li>
 *   <li>A denial must be reported as a denied {@link PolicyResult}, never as an
 *       exception. Exceptions are reserved for a broken context or a broken
 *       dependency.</li>
 *   <li>Implementations should be thread-safe; the ones in this library are.</li>
 * </ul>
 *
 * @param <T> type of context evaluated by this policy
 * @see Policies
 * @see PolicyResult
 */
public interface Policy<T> {

    /**
     * Returns the stable identifier of this policy.
     *
     * @return non-blank policy identifier
     */
    String id();

    /**
     * Evaluates a context.
     *
     * @param context business context to decide on
     * @return the outcome, never null
     */
    PolicyResult evaluate(T context);

    /**
     * Evaluates a context and fails when it is denied.
     *
     * <p>Shorthand for {@code evaluate(context).requireAllowed()}, meant for the
     * boundary of an application where a denial must abort the operation.</p>
     *
     * @param context business context to decide on
     * @throws PolicyViolationException carrying every violation, when denied
     */
    default void enforce(T context) {
        Objects.requireNonNull(
                evaluate(context),
                "policy must not return null: " + id()
        ).requireAllowed();
    }
}
