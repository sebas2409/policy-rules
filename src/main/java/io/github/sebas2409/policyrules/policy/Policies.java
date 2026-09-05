package io.github.sebas2409.policyrules.policy;

import io.github.sebas2409.policyrules.rule.Rule;
import io.github.sebas2409.policyrules.rule.trace.ExplainableRule;
import io.github.sebas2409.policyrules.rule.trace.ExplainableRules;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Factories for the policies an application normally needs.
 *
 * <p>This is the entry point of the library: policies are values, so they are
 * built here rather than by writing a class per rule. Everything returned is
 * immutable and thread-safe, and is meant to be built once and reused.</p>
 *
 * <h2>Single conditions</h2>
 * {@snippet lang = "java":
 * Policy<Booking> active = Policies.require(
 *         "booking-must-be-active", Booking::active,
 *         "BOOKING_INACTIVE", "The booking must be active");
 *
 * Policy<Booking> notCancelled = Policies.forbid(
 *         "booking-must-not-be-cancelled", Booking::cancelled,
 *         "BOOKING_CANCELLED", "The booking was cancelled");
 *}
 *
 * <h2>Combining them</h2>
 * <p>Two strategies, chosen by what the caller does with the answer:</p>
 * <ul>
 *   <li>{@link #allOf(String, List)} evaluates every member and reports every
 *       reason. This is what a form or an API wants, so the user fixes all the
 *       problems at once.</li>
 *   <li>{@link #firstFailureOf(String, List)} stops at the first denial. This is
 *       what a hot path wants when later checks are expensive, or when the
 *       first reason is enough.</li>
 * </ul>
 *
 * {@snippet lang = "java":
 * Policy<Booking> canBeConfirmed = Policies.allOf(
 *         "booking-can-be-confirmed",
 *         List.of(active, notCancelled, withinWeeklyLimit));
 *}
 *
 * <h2>Explaining a denial</h2>
 * <p>{@link #requireExplained(String, ExplainableRule, String, String)} builds
 * the same policy over a rule that can report which node decided the
 * evaluation, and attaches that report to the violation.</p>
 *
 * @see Policy
 * @see PolicyResult
 */
public final class Policies {

    /**
     * Metadata key under which an explained policy stores the full
     * {@link io.github.sebas2409.policyrules.rule.trace.RuleTrace} of a denial.
     *
     * <p>A violation factory that uses this key for its own data will have it
     * overwritten.</p>
     */
    public static final String TRACE_METADATA_KEY = "trace";

    /**
     * Metadata key under which an explained policy stores the node that
     * accounts for a denial, when a single node accounts for it.
     *
     * @see io.github.sebas2409.policyrules.rule.trace.RuleTrace#culprit()
     */
    public static final String CULPRIT_METADATA_KEY = "culprit";

    private Policies() {
    }

    /**
     * Creates a policy that allows a context only when the rule holds.
     *
     * <p>The violation is built once and reused, so this overload is the right
     * one whenever the explanation does not depend on the context.</p>
     *
     * @param id      stable, non-blank policy identifier
     * @param rule    condition that must hold
     * @param code    violation code reported when it does not hold
     * @param message violation message reported when it does not hold
     * @param <T>     context type
     * @return the policy
     * @throws NullPointerException     if an argument is null
     * @throws IllegalArgumentException if an argument is blank
     */
    public static <T> Policy<T> require(String id, Rule<T> rule, String code, String message) {
        var violation = new PolicyViolation(code, message);
        return new RequiredPolicy<>(id, rule, context -> violation);
    }

    /**
     * Creates a policy that allows a context only when the rule holds, with an
     * explanation built from the denied context.
     *
     * <p>Use this overload to report the values that caused the denial. The
     * factory is only invoked when the rule does not hold, so building a
     * detailed explanation costs nothing on the allowed path:</p>
     *
     * {@snippet lang = "java":
     * Policy<Booking> withinWeeklyLimit = Policies.require(
     *         "weekly-limit",
     *         booking -> booking.weeklyCount() < 3,
     *         booking -> new PolicyViolation(
     *                 "WEEKLY_LIMIT_REACHED",
     *                 "Weekly booking limit reached",
     *                 Map.of("current", booking.weeklyCount(), "maximum", 3)));
     *}
     *
     * @param id               stable, non-blank policy identifier
     * @param rule             condition that must hold
     * @param violationFactory builds the violation from the denied context;
     *                         it must not return null
     * @param <T>              context type
     * @return the policy
     * @throws NullPointerException     if an argument is null
     * @throws IllegalArgumentException if {@code id} is blank
     */
    public static <T> Policy<T> require(
            String id,
            Rule<T> rule,
            Function<T, PolicyViolation> violationFactory
    ) {
        return new RequiredPolicy<>(id, rule, violationFactory);
    }

    /**
     * Creates a policy that denies a context when the rule holds.
     *
     * <p>The mirror image of {@link #require(String, Rule, String, String)}, for
     * conditions that read better stated as what must not happen.</p>
     *
     * @param id      stable, non-blank policy identifier
     * @param rule    condition that must not hold
     * @param code    violation code reported when it holds
     * @param message violation message reported when it holds
     * @param <T>     context type
     * @return the policy
     * @throws NullPointerException     if an argument is null
     * @throws IllegalArgumentException if an argument is blank
     */
    public static <T> Policy<T> forbid(String id, Rule<T> rule, String code, String message) {
        Objects.requireNonNull(rule, "rule must not be null");
        return require(id, rule.not(), code, message);
    }

    /**
     * Creates a policy that denies a context when the rule holds, with an
     * explanation built from the denied context.
     *
     * @param id               stable, non-blank policy identifier
     * @param rule             condition that must not hold
     * @param violationFactory builds the violation from the denied context;
     *                         it must not return null
     * @param <T>              context type
     * @return the policy
     * @throws NullPointerException     if an argument is null
     * @throws IllegalArgumentException if {@code id} is blank
     */
    public static <T> Policy<T> forbid(
            String id,
            Rule<T> rule,
            Function<T, PolicyViolation> violationFactory
    ) {
        Objects.requireNonNull(rule, "rule must not be null");
        return new RequiredPolicy<>(id, rule.not(), violationFactory);
    }

    /**
     * Creates a policy that allows a context only when the rule holds, and
     * reports how the rule decided.
     *
     * <p>The behaviour is that of
     * {@link #require(String, Rule, String, String)}, plus two entries in the
     * metadata of the violation when the context is denied:</p>
     *
     * <ul>
     *   <li>{@link #TRACE_METADATA_KEY}, the whole
     *       {@link io.github.sebas2409.policyrules.rule.trace.RuleTrace}.</li>
     *   <li>{@link #CULPRIT_METADATA_KEY}, the node that accounts for the
     *       denial, when a single node accounts for it.</li>
     * </ul>
     *
     * {@snippet lang = "java":
     * Policy<Application> withinLimits = Policies.requireExplained(
     *         "within-limits",
     *         compiler.compileExplainable(definition),
     *         "ABOVE_LIMIT", "The application is outside the accepted limits");
     *
     * PolicyResult result = withinLimits.evaluate(application);
     * result.firstViolation()
     *         .map(violation -> violation.metadata().get(Policies.CULPRIT_METADATA_KEY))
     *         .ifPresent(System.out::println);
     *}
     *
     * <p>The rule is evaluated once, and the trace describes that very
     * evaluation. Building a trace costs an allocation per node, so this
     * factory is for policies whose denials someone reads; a hot path with no
     * reader keeps using {@link #require(String, Rule, String, String)}.</p>
     *
     * @param id      stable, non-blank policy identifier
     * @param rule    condition that must hold, able to explain itself
     * @param code    violation code reported when it does not hold
     * @param message violation message reported when it does not hold
     * @param <T>     context type
     * @return the policy
     * @throws NullPointerException     if an argument is null
     * @throws IllegalArgumentException if an argument is blank
     */
    public static <T> Policy<T> requireExplained(
            String id,
            ExplainableRule<T> rule,
            String code,
            String message
    ) {
        var violation = new PolicyViolation(code, message);
        return new ExplainedPolicy<>(id, rule, context -> violation);
    }

    /**
     * Creates a policy that allows a context only when the rule holds, with an
     * explanation built from the denied context and the trace of the rule.
     *
     * <p>The violation returned by the factory is enriched with the same
     * metadata described in
     * {@link #requireExplained(String, ExplainableRule, String, String)}.</p>
     *
     * @param id               stable, non-blank policy identifier
     * @param rule             condition that must hold, able to explain itself
     * @param violationFactory builds the violation from the denied context;
     *                         it must not return null
     * @param <T>              context type
     * @return the policy
     * @throws NullPointerException     if an argument is null
     * @throws IllegalArgumentException if {@code id} is blank
     */
    public static <T> Policy<T> requireExplained(
            String id,
            ExplainableRule<T> rule,
            Function<T, PolicyViolation> violationFactory
    ) {
        return new ExplainedPolicy<>(id, rule, violationFactory);
    }

    /**
     * Creates a policy that denies a context when the rule holds, and reports
     * how the rule decided.
     *
     * <p>The mirror image of
     * {@link #requireExplained(String, ExplainableRule, String, String)}. The
     * negation stays visible in the trace: the reported node is the negation,
     * and below it the rule that held.</p>
     *
     * @param id      stable, non-blank policy identifier
     * @param rule    condition that must not hold, able to explain itself
     * @param code    violation code reported when it holds
     * @param message violation message reported when it holds
     * @param <T>     context type
     * @return the policy
     * @throws NullPointerException     if an argument is null
     * @throws IllegalArgumentException if an argument is blank
     */
    public static <T> Policy<T> forbidExplained(
            String id,
            ExplainableRule<T> rule,
            String code,
            String message
    ) {
        Objects.requireNonNull(rule, "rule must not be null");
        return requireExplained(id, ExplainableRules.not(rule), code, message);
    }

    /**
     * Creates a policy that evaluates every member and accumulates every reason.
     *
     * <p>Members are evaluated in declaration order and their violations are
     * reported in that same order, so a caller can present them predictably.</p>
     *
     * @param id       stable, non-blank identifier for the composite
     * @param policies non-empty members, in evaluation order
     * @param <T>      context type
     * @return the composite policy
     * @throws NullPointerException     if an argument or a member is null
     * @throws IllegalArgumentException if {@code id} is blank or there are no members
     */
    public static <T> Policy<T> allOf(String id, List<? extends Policy<T>> policies) {
        return new CompositePolicy<>(id, policies, false);
    }

    /**
     * Creates a policy that evaluates every member and accumulates every reason.
     *
     * @param id       stable, non-blank identifier for the composite
     * @param policies non-empty members, in evaluation order
     * @param <T>      context type
     * @return the composite policy
     * @throws NullPointerException     if an argument or a member is null
     * @throws IllegalArgumentException if {@code id} is blank or there are no members
     * @see #allOf(String, List)
     */
    @SafeVarargs
    public static <T> Policy<T> allOf(String id, Policy<T>... policies) {
        return allOf(id, List.of(policies));
    }

    /**
     * Creates a policy that stops at the first denied member.
     *
     * <p>Members after the first denial are never evaluated, which matters when
     * a later check is expensive.</p>
     *
     * @param id       stable, non-blank identifier for the composite
     * @param policies non-empty members, in evaluation order
     * @param <T>      context type
     * @return the composite policy
     * @throws NullPointerException     if an argument or a member is null
     * @throws IllegalArgumentException if {@code id} is blank or there are no members
     */
    public static <T> Policy<T> firstFailureOf(String id, List<? extends Policy<T>> policies) {
        return new CompositePolicy<>(id, policies, true);
    }

    /**
     * Creates a policy that stops at the first denied member.
     *
     * @param id       stable, non-blank identifier for the composite
     * @param policies non-empty members, in evaluation order
     * @param <T>      context type
     * @return the composite policy
     * @throws NullPointerException     if an argument or a member is null
     * @throws IllegalArgumentException if {@code id} is blank or there are no members
     * @see #firstFailureOf(String, List)
     */
    @SafeVarargs
    public static <T> Policy<T> firstFailureOf(String id, Policy<T>... policies) {
        return firstFailureOf(id, List.of(policies));
    }

    /**
     * Reuses a policy for a wider context type.
     *
     * <p>Lets a policy written against a focused type be applied inside an
     * aggregate without rewriting it, which is what keeps a policy catalog from
     * growing one variant per caller:</p>
     *
     * {@snippet lang = "java":
     * Policy<Customer> verified = Policies.require(
     *         "customer-verified", Customer::verified,
     *         "CUSTOMER_NOT_VERIFIED", "The customer is not verified");
     *
     * Policy<Order> orderFromVerifiedCustomer =
     *         Policies.adapt(verified, Order::customer);
     *}
     *
     * <p>The adapted policy keeps the identifier of the original.</p>
     *
     * @param policy    policy to reuse
     * @param extractor obtains the evaluated context from the wider context;
     *                  it must not return null
     * @param <T>       context type evaluated by {@code policy}
     * @param <U>       wider context type
     * @return a policy over {@code U}
     * @throws NullPointerException if an argument is null
     */
    public static <T, U> Policy<U> adapt(Policy<T> policy, Function<U, ? extends T> extractor) {
        return new AdaptedPolicy<>(policy, extractor);
    }

    /**
     * Creates a policy that allows every context.
     *
     * <p>The neutral element of a composite: useful as a placeholder while a
     * feature is being built, and to keep an assembled list non-empty.</p>
     *
     * @param id  stable, non-blank policy identifier
     * @param <T> context type
     * @return a policy that never denies
     * @throws NullPointerException     if {@code id} is null
     * @throws IllegalArgumentException if {@code id} is blank
     */
    public static <T> Policy<T> allow(String id) {
        var validatedId = requireId(id);
        return new Policy<>() {

            @Override
            public String id() {
                return validatedId;
            }

            @Override
            public PolicyResult evaluate(T context) {
                return PolicyResult.allow();
            }

            @Override
            public String toString() {
                return "Policy[" + validatedId + ", always allowed]";
            }
        };
    }

    /**
     * Creates a policy that denies every context with a fixed reason.
     *
     * <p>Useful to disable an operation without removing its wiring, for example
     * behind a feature flag.</p>
     *
     * @param id      stable, non-blank policy identifier
     * @param code    violation code always reported
     * @param message violation message always reported
     * @param <T>     context type
     * @return a policy that never allows
     * @throws NullPointerException     if an argument is null
     * @throws IllegalArgumentException if an argument is blank
     */
    public static <T> Policy<T> deny(String id, String code, String message) {
        var validatedId = requireId(id);
        var result = PolicyResult.deny(new PolicyViolation(code, message));
        return new Policy<>() {

            @Override
            public String id() {
                return validatedId;
            }

            @Override
            public PolicyResult evaluate(T context) {
                return result;
            }

            @Override
            public String toString() {
                return "Policy[" + validatedId + ", always denied]";
            }
        };
    }

    /**
     * Validates a policy identifier.
     *
     * @param id identifier to validate
     * @return the identifier
     * @throws NullPointerException     if {@code id} is null
     * @throws IllegalArgumentException if {@code id} is blank
     */
    static String requireId(String id) {
        Objects.requireNonNull(id, "id must not be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        return id;
    }
}
