package io.github.sebas2409.policyrules.policy;

import java.util.Objects;
import java.util.function.Function;

/**
 * Applies a policy to a wider context by extracting the part it evaluates.
 *
 * <p>Package-private implementation behind {@link Policies#adapt}.</p>
 *
 * @param <T> context type the delegate evaluates
 * @param <U> wider context type accepted by this policy
 */
final class AdaptedPolicy<T, U> implements Policy<U> {

    /** Policy written against the narrower context. */
    private final Policy<T> delegate;

    /** Obtains the narrower context from the wider one. */
    private final Function<U, ? extends T> extractor;

    /**
     * Creates the adapter.
     *
     * @param delegate  policy to reuse
     * @param extractor obtains the evaluated context from the wider context
     * @throws NullPointerException if an argument is null
     */
    AdaptedPolicy(Policy<T> delegate, Function<U, ? extends T> extractor) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.extractor = Objects.requireNonNull(extractor, "extractor must not be null");
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public PolicyResult evaluate(U context) {
        return delegate.evaluate(Objects.requireNonNull(
                extractor.apply(context),
                "extractor must not return null: " + id()
        ));
    }

    @Override
    public String toString() {
        return delegate.toString();
    }
}
