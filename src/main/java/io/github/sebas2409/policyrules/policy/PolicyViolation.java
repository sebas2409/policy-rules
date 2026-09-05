package io.github.sebas2409.policyrules.policy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The reason a policy denied a context.
 *
 * <p>A violation is what makes a denial actionable. It separates the stable
 * {@code code} that callers branch on from the {@code message} meant for a
 * human, and carries the values that explain the decision in {@code metadata}:</p>
 *
 * {@snippet lang = "java":
 * PolicyViolation violation = new PolicyViolation(
 *         "WEEKLY_LIMIT_REACHED",
 *         "Weekly booking limit reached",
 *         Map.of("current", 3, "maximum", 3)
 * );
 *}
 *
 * <h2>Choosing a code</h2>
 * <p>Codes are part of the contract with the callers of the application: an API
 * client, a front end that translates them, an operator reading a log. Treat
 * them as an enumeration that only grows, and keep the human wording in
 * {@code message} where it can change freely.</p>
 *
 * <h2>Metadata</h2>
 * <p>Metadata usually holds the values that were compared, so the caller can
 * explain the denial without repeating the business logic. Values must be
 * non-null, and should be simple types that any serializer can handle. Do not
 * put personal or sensitive data here: violations tend to end up in logs and in
 * API responses.</p>
 *
 * <p>The record is immutable, and its metadata is defensively copied.</p>
 *
 * @param code     stable, machine-readable identifier of the reason
 * @param message  human-readable explanation
 * @param metadata immutable values that explain the decision
 */
public record PolicyViolation(
        String code,
        String message,
        Map<String, Object> metadata
) {

    /**
     * Validates the text fields and defensively copies the metadata.
     *
     * @throws NullPointerException     if an argument, a metadata key or a
     *                                  metadata value is null
     * @throws IllegalArgumentException if {@code code} or {@code message} is blank
     */
    public PolicyViolation {
        code = requireText(code, "code");
        message = requireText(message, "message");
        metadata = Map.copyOf(
                Objects.requireNonNull(metadata, "metadata must not be null")
        );
    }

    /**
     * Creates a violation without metadata.
     *
     * @param code    stable, machine-readable identifier of the reason
     * @param message human-readable explanation
     * @throws NullPointerException     if an argument is null
     * @throws IllegalArgumentException if an argument is blank
     */
    public PolicyViolation(String code, String message) {
        this(code, message, Map.of());
    }

    /**
     * Returns a copy of this violation with one extra metadata entry.
     *
     * <p>Existing entries with the same key are replaced. Useful to enrich a
     * violation produced by a reusable policy with context known only at the
     * call site.</p>
     *
     * @param key   metadata key
     * @param value metadata value
     * @return a new violation carrying the entry
     * @throws NullPointerException if an argument is null
     */
    public PolicyViolation with(String key, Object value) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        var enriched = new LinkedHashMap<>(metadata);
        enriched.put(key, value);
        return new PolicyViolation(code, message, enriched);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
