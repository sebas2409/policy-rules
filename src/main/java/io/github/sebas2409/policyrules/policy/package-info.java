/**
 * Business decisions that can explain themselves.
 *
 * <p>A {@link io.github.sebas2409.policyrules.policy.Policy} pairs a condition with the meaning
 * of failing it, and answers with a {@link io.github.sebas2409.policyrules.policy.PolicyResult}:
 * allowed, or denied with one
 * {@link io.github.sebas2409.policyrules.policy.PolicyViolation} per reason. That is the whole
 * model; {@link io.github.sebas2409.policyrules.policy.Policies} builds and combines it.</p>
 *
 * {@snippet lang = "java":
 * Policy<Booking> canBeConfirmed = Policies.allOf("booking-can-be-confirmed", List.of(
 *         Policies.require("booking-must-be-active", Booking::active,
 *                 "BOOKING_INACTIVE", "The booking must be active"),
 *         Policies.forbid("booking-must-not-be-cancelled", Booking::cancelled,
 *                 "BOOKING_CANCELLED", "The booking was cancelled")
 * ));
 *
 * PolicyResult result = canBeConfirmed.evaluate(booking);
 *}
 *
 * <h2>Denials are results, not exceptions</h2>
 * <p>A denial is an expected outcome of a business operation, so it is returned
 * as a value that carries every reason. Only the boundary of an application,
 * where a denial must abort the operation, turns it into a
 * {@link io.github.sebas2409.policyrules.policy.PolicyViolationException} through
 * {@link io.github.sebas2409.policyrules.policy.PolicyResult#requireAllowed()} or
 * {@link io.github.sebas2409.policyrules.policy.Policy#enforce(java.lang.Object)}.</p>
 *
 * <h2>Relationship with rules</h2>
 * <p>Conditions live in {@link io.github.sebas2409.policyrules.rule} and know nothing about
 * violations, which is what lets the same condition be reused by several
 * policies and be produced either by code or by configuration.</p>
 */
package io.github.sebas2409.policyrules.policy;
