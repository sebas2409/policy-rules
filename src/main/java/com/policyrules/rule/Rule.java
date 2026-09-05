package com.policyrules.rule;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A composable, side-effect free business condition over a context object.
 *
 * <p>A rule answers a single yes/no question about a context and knows nothing
 * about why the answer matters. Attaching a meaning to the answer (a violation
 * code, a message, an HTTP status) is the responsibility of a
 * {@code com.policyrules.policy.Policy}. Keeping both concerns apart is what
 * allows the same rule to be reused by several policies.</p>
 *
 * <p>Rules compose with short-circuit boolean semantics, exactly like the
 * {@code &&}, {@code ||} and {@code !} operators:</p>
 *
 * {@snippet lang = "java":
 * Rule<Customer> adult = customer -> customer.age() >= 18;
 * Rule<Customer> verified = Customer::verified;
 *
 * Rule<Customer> eligible = adult.and(verified);
 * boolean ok = eligible.matches(customer);
 *}
 *
 * <h2>Implementation contract</h2>
 * <ul>
 *   <li>{@link #matches(Object)} must be free of observable side effects: the
 *       library may call it zero, one or many times for the same context.</li>
 *   <li>Implementations should be thread-safe. Lambdas over immutable state,
 *       which is the common case, already are.</li>
 *   <li>A rule may reject an invalid context by throwing, but a rule that
 *       simply does not hold must return {@code false} instead of throwing.</li>
 * </ul>
 *
 * @param <T> type of context inspected by the rule
 * @see Rules
 */
@FunctionalInterface
public interface Rule<T> {

    /**
     * Tests whether the context satisfies this rule.
     *
     * @param context business context to inspect
     * @return {@code true} when the rule holds for the context
     */
    boolean matches(T context);

    /**
     * Returns a rule that holds only when this rule and {@code other} hold.
     *
     * <p>{@code other} is not evaluated when this rule returns {@code false}.</p>
     *
     * @param other second operand
     * @return the conjunction of both rules
     * @throws NullPointerException if {@code other} is null
     */
    default Rule<T> and(Rule<T> other) {
        Objects.requireNonNull(other, "other must not be null");
        return context -> this.matches(context) && other.matches(context);
    }

    /**
     * Returns a rule that holds when this rule or {@code other} holds.
     *
     * <p>{@code other} is not evaluated when this rule returns {@code true}.</p>
     *
     * @param other second operand
     * @return the disjunction of both rules
     * @throws NullPointerException if {@code other} is null
     */
    default Rule<T> or(Rule<T> other) {
        Objects.requireNonNull(other, "other must not be null");
        return context -> this.matches(context) || other.matches(context);
    }

    /**
     * Returns a rule with the inverse result of this one.
     *
     * @return the negation of this rule
     */
    default Rule<T> not() {
        return context -> !this.matches(context);
    }

    /**
     * Reuses this rule for a different, richer context type.
     *
     * <p>This is how a rule written against a small, focused type is applied
     * inside a larger aggregate without duplicating the condition:</p>
     *
     * {@snippet lang = "java":
     * Rule<Customer> adult = customer -> customer.age() >= 18;
     * Rule<Order> orderedByAdult = adult.adapt(Order::customer);
     *}
     *
     * @param extractor obtains the inspected context from the wider context;
     *                  it must not return null
     * @param <U>       wider context type
     * @return a rule over {@code U} that delegates to this rule
     * @throws NullPointerException if {@code extractor} is null, or if it
     *                              returns null while the rule is evaluated
     */
    default <U> Rule<U> adapt(Function<U, ? extends T> extractor) {
        Objects.requireNonNull(extractor, "extractor must not be null");
        return context -> this.matches(Objects.requireNonNull(
                extractor.apply(context),
                "extractor must not return null"
        ));
    }

    /**
     * Views this rule as a {@link Predicate}, for interoperability with the
     * standard library (streams, {@code Optional.filter}, and so on).
     *
     * @return an equivalent predicate
     */
    default Predicate<T> asPredicate() {
        return this::matches;
    }
}
