package com.policyrules.rule.definition;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Type-safe, read-only view over the raw parameters of an
 * {@link AtomicRuleDefinition}.
 *
 * <p>Parameters arrive from outside the application, so their runtime types
 * depend on whatever produced them: a JSON parser yields {@code Integer} or
 * {@code Double}, a document database may yield {@code Long} or a decimal
 * wrapper, a properties file yields only {@code String}. Reading them by hand
 * turns every {@link RuleFactory} into a pile of casts and null checks. This
 * class does that work once, and fails with a message that names the offending
 * parameter:</p>
 *
 * {@snippet lang = "java":
 * registry.register("minimum-age", parameters -> {
 *     int minimum = parameters.intValue("minimum");
 *     return customer -> customer.age() >= minimum;
 * });
 *}
 *
 * <h2>Read parameters when the rule is built, not when it runs</h2>
 * <p>As in the example above, a factory should read its parameters into local
 * variables and capture those in the returned lambda. Reading them inside
 * {@code matches} would repeat the conversion on every evaluation and would
 * turn a configuration mistake into a failure in the middle of a business
 * decision instead of a failure at compile time.</p>
 *
 * <h2>Accepted values</h2>
 * <table class="striped">
 *   <caption>Conversion rules applied by the typed accessors</caption>
 *   <thead>
 *     <tr><th>Requested type</th><th>Accepted values</th></tr>
 *   </thead>
 *   <tbody>
 *     <tr><td>{@code String}</td><td>any {@link CharSequence}</td></tr>
 *     <tr><td>{@code int}, {@code long}</td>
 *         <td>any {@link Number} without a fractional part, or text that parses
 *             as such a number, within the range of the requested type</td></tr>
 *     <tr><td>{@code double}, {@code BigDecimal}</td>
 *         <td>any {@link Number}, or text that parses as a number</td></tr>
 *     <tr><td>{@code boolean}</td>
 *         <td>{@link Boolean}, or the text {@code "true"} / {@code "false"},
 *             ignoring case and surrounding blanks</td></tr>
 *     <tr><td>an {@code enum}</td>
 *         <td>an instance of that enum, or its constant name, ignoring case</td></tr>
 *     <tr><td>anything else</td><td>an instance of the requested type</td></tr>
 *   </tbody>
 * </table>
 *
 * <p>Numbers are converted through {@link BigDecimal}, so a value stored as
 * {@code 18}, {@code 18.0} or {@code "18"} all read as the same {@code int}, and
 * a value that would silently lose precision or overflow is rejected instead.</p>
 *
 * <p>Instances are immutable and thread-safe.</p>
 *
 * @see RuleFactory
 * @see RuleParameterException
 */
public final class RuleParameters {

    private static final RuleParameters EMPTY = new RuleParameters(Map.of());

    /** Immutable raw values, never containing a null key or value. */
    private final Map<String, Object> values;

    private RuleParameters(Map<String, Object> values) {
        this.values = values;
    }

    /**
     * Wraps raw parameter values.
     *
     * <p>The map is defensively copied, so later changes to the source map do
     * not affect the returned instance.</p>
     *
     * @param values raw values keyed by parameter name
     * @return an immutable view over the values
     * @throws NullPointerException if the map, a key or a value is null
     */
    public static RuleParameters of(Map<String, Object> values) {
        Objects.requireNonNull(values, "values must not be null");
        return values.isEmpty() ? EMPTY : new RuleParameters(Map.copyOf(values));
    }

    /**
     * Returns the shared empty instance, for rule types without parameters.
     *
     * @return parameters without any value
     */
    public static RuleParameters empty() {
        return EMPTY;
    }

    /**
     * Returns the raw values.
     *
     * @return immutable map of raw parameter values
     */
    public Map<String, Object> asMap() {
        return values;
    }

    /**
     * Returns the names of every supplied parameter.
     *
     * @return immutable set of parameter names
     */
    public Set<String> names() {
        return values.keySet();
    }

    /**
     * Indicates whether a parameter was supplied.
     *
     * @param name parameter name
     * @return {@code true} when the parameter is present
     * @throws NullPointerException if {@code name} is null
     */
    public boolean contains(String name) {
        return values.containsKey(Objects.requireNonNull(name, "name must not be null"));
    }

    /**
     * Indicates whether no parameter was supplied.
     *
     * @return {@code true} when there are no parameters
     */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * Returns the raw value of a parameter, if present.
     *
     * @param name parameter name
     * @return the raw value, or an empty optional when absent
     * @throws NullPointerException if {@code name} is null
     */
    public Optional<Object> find(String name) {
        return Optional.ofNullable(
                values.get(Objects.requireNonNull(name, "name must not be null"))
        );
    }

    /**
     * Reads a required text parameter.
     *
     * @param name parameter name
     * @return the text value
     * @throws RuleParameterException if the parameter is missing or is not text
     * @throws NullPointerException   if {@code name} is null
     */
    public String string(String name) {
        return value(name, String.class);
    }

    /**
     * Reads an optional text parameter.
     *
     * @param name         parameter name
     * @param defaultValue value returned when the parameter is absent
     * @return the text value, or {@code defaultValue}
     * @throws RuleParameterException if the parameter is present but is not text
     * @throws NullPointerException   if {@code name} is null
     */
    public String string(String name, String defaultValue) {
        return value(name, String.class, defaultValue);
    }

    /**
     * Reads a required {@code int} parameter.
     *
     * @param name parameter name
     * @return the numeric value
     * @throws RuleParameterException if the parameter is missing, is not a whole
     *                                number, or does not fit in an {@code int}
     * @throws NullPointerException   if {@code name} is null
     */
    public int intValue(String name) {
        return value(name, Integer.class);
    }

    /**
     * Reads an optional {@code int} parameter.
     *
     * @param name         parameter name
     * @param defaultValue value returned when the parameter is absent
     * @return the numeric value, or {@code defaultValue}
     * @throws RuleParameterException if the parameter is present but unusable
     * @throws NullPointerException   if {@code name} is null
     */
    public int intValue(String name, int defaultValue) {
        return value(name, Integer.class, defaultValue);
    }

    /**
     * Reads a required {@code long} parameter.
     *
     * @param name parameter name
     * @return the numeric value
     * @throws RuleParameterException if the parameter is missing, is not a whole
     *                                number, or does not fit in a {@code long}
     * @throws NullPointerException   if {@code name} is null
     */
    public long longValue(String name) {
        return value(name, Long.class);
    }

    /**
     * Reads an optional {@code long} parameter.
     *
     * @param name         parameter name
     * @param defaultValue value returned when the parameter is absent
     * @return the numeric value, or {@code defaultValue}
     * @throws RuleParameterException if the parameter is present but unusable
     * @throws NullPointerException   if {@code name} is null
     */
    public long longValue(String name, long defaultValue) {
        return value(name, Long.class, defaultValue);
    }

    /**
     * Reads a required {@code double} parameter.
     *
     * @param name parameter name
     * @return the numeric value
     * @throws RuleParameterException if the parameter is missing or is not numeric
     * @throws NullPointerException   if {@code name} is null
     */
    public double doubleValue(String name) {
        return value(name, Double.class);
    }

    /**
     * Reads an optional {@code double} parameter.
     *
     * @param name         parameter name
     * @param defaultValue value returned when the parameter is absent
     * @return the numeric value, or {@code defaultValue}
     * @throws RuleParameterException if the parameter is present but is not numeric
     * @throws NullPointerException   if {@code name} is null
     */
    public double doubleValue(String name, double defaultValue) {
        return value(name, Double.class, defaultValue);
    }

    /**
     * Reads a required decimal parameter, without loss of precision.
     *
     * <p>Preferred over {@link #doubleValue(String)} for monetary amounts and
     * any other value where rounding matters.</p>
     *
     * @param name parameter name
     * @return the numeric value
     * @throws RuleParameterException if the parameter is missing or is not numeric
     * @throws NullPointerException   if {@code name} is null
     */
    public BigDecimal decimal(String name) {
        return value(name, BigDecimal.class);
    }

    /**
     * Reads an optional decimal parameter.
     *
     * @param name         parameter name
     * @param defaultValue value returned when the parameter is absent
     * @return the numeric value, or {@code defaultValue}
     * @throws RuleParameterException if the parameter is present but is not numeric
     * @throws NullPointerException   if {@code name} is null
     */
    public BigDecimal decimal(String name, BigDecimal defaultValue) {
        return value(name, BigDecimal.class, defaultValue);
    }

    /**
     * Reads a required {@code boolean} parameter.
     *
     * @param name parameter name
     * @return the boolean value
     * @throws RuleParameterException if the parameter is missing or is not a boolean
     * @throws NullPointerException   if {@code name} is null
     */
    public boolean booleanValue(String name) {
        return value(name, Boolean.class);
    }

    /**
     * Reads an optional {@code boolean} parameter.
     *
     * @param name         parameter name
     * @param defaultValue value returned when the parameter is absent
     * @return the boolean value, or {@code defaultValue}
     * @throws RuleParameterException if the parameter is present but is not a boolean
     * @throws NullPointerException   if {@code name} is null
     */
    public boolean booleanValue(String name, boolean defaultValue) {
        return value(name, Boolean.class, defaultValue);
    }

    /**
     * Reads a required parameter as a value of the given type.
     *
     * <p>This is the general form behind every typed accessor; use it for enums
     * and for any type not covered by a dedicated method.</p>
     *
     * @param name parameter name
     * @param type requested type
     * @param <V>  requested type
     * @return the converted value
     * @throws RuleParameterException if the parameter is missing or cannot be
     *                                converted to {@code type}
     * @throws NullPointerException   if an argument is null
     */
    public <V> V value(String name, Class<V> type) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(type, "type must not be null");
        var raw = values.get(name);
        if (raw == null) {
            throw new RuleParameterException(
                    name,
                    "Parameter '" + name + "' is required"
            );
        }
        return convert(name, raw, type);
    }

    /**
     * Reads an optional parameter as a value of the given type.
     *
     * @param name         parameter name
     * @param type         requested type
     * @param defaultValue value returned when the parameter is absent
     * @param <V>          requested type
     * @return the converted value, or {@code defaultValue}
     * @throws RuleParameterException if the parameter is present but cannot be
     *                                converted to {@code type}
     * @throws NullPointerException   if {@code name} or {@code type} is null
     */
    public <V> V value(String name, Class<V> type, V defaultValue) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(type, "type must not be null");
        var raw = values.get(name);
        return raw == null ? defaultValue : convert(name, raw, type);
    }

    /**
     * Reads a required list parameter, converting every element.
     *
     * <p>The stored value must be a collection; each element is converted with
     * the same rules as {@link #value(String, Class)}.</p>
     *
     * {@snippet lang = "java":
     * registry.register("country-in", parameters -> {
     *     Set<String> allowed = Set.copyOf(parameters.list("countries", String.class));
     *     return customer -> allowed.contains(customer.country());
     * });
     *}
     *
     * @param name        parameter name
     * @param elementType requested element type
     * @param <V>         requested element type
     * @return an immutable list of converted elements
     * @throws RuleParameterException if the parameter is missing, is not a
     *                                collection, or holds an element that cannot
     *                                be converted
     * @throws NullPointerException   if an argument is null
     */
    public <V> List<V> list(String name, Class<V> elementType) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(elementType, "elementType must not be null");
        var raw = values.get(name);
        if (raw == null) {
            throw new RuleParameterException(
                    name,
                    "Parameter '" + name + "' is required"
            );
        }
        return toList(name, raw, elementType);
    }

    /**
     * Reads an optional list parameter, converting every element.
     *
     * @param name         parameter name
     * @param elementType  requested element type
     * @param defaultValue value returned when the parameter is absent
     * @param <V>          requested element type
     * @return an immutable list of converted elements, or {@code defaultValue}
     * @throws RuleParameterException if the parameter is present but unusable
     * @throws NullPointerException   if {@code name} or {@code elementType} is null
     */
    public <V> List<V> list(String name, Class<V> elementType, List<V> defaultValue) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(elementType, "elementType must not be null");
        var raw = values.get(name);
        return raw == null ? defaultValue : toList(name, raw, elementType);
    }

    /**
     * Reads a required parameter that holds a nested group of parameters.
     *
     * <p>Useful for rule types whose configuration is structured, such as
     * {@code {"window": {"days": 7, "maximum": 3}}}.</p>
     *
     * @param name parameter name
     * @return the nested parameters
     * @throws RuleParameterException if the parameter is missing, is not a map,
     *                                or has a non-text key
     * @throws NullPointerException   if {@code name} is null
     */
    public RuleParameters group(String name) {
        Objects.requireNonNull(name, "name must not be null");
        var raw = values.get(name);
        if (raw == null) {
            throw new RuleParameterException(
                    name,
                    "Parameter '" + name + "' is required"
            );
        }
        return toGroup(name, raw);
    }

    /**
     * Reads an optional parameter that holds a nested group of parameters.
     *
     * @param name         parameter name
     * @param defaultValue value returned when the parameter is absent
     * @return the nested parameters, or {@code defaultValue}
     * @throws RuleParameterException if the parameter is present but is not a map
     * @throws NullPointerException   if {@code name} is null
     */
    public RuleParameters group(String name, RuleParameters defaultValue) {
        Objects.requireNonNull(name, "name must not be null");
        var raw = values.get(name);
        return raw == null ? defaultValue : toGroup(name, raw);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RuleParameters parameters
                && values.equals(parameters.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "RuleParameters" + values;
    }

    private <V> List<V> toList(String name, Object raw, Class<V> elementType) {
        if (!(raw instanceof Collection<?> elements)) {
            throw typeError(name, raw, "a list of " + elementType.getSimpleName());
        }
        var converted = new ArrayList<V>(elements.size());
        for (var element : elements) {
            if (element == null) {
                throw new RuleParameterException(
                        name,
                        "Parameter '" + name + "' must not contain null elements"
                );
            }
            converted.add(convert(name, element, elementType));
        }
        return List.copyOf(converted);
    }

    private RuleParameters toGroup(String name, Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw typeError(name, raw, "a group of parameters");
        }
        var group = new LinkedHashMap<String, Object>(map.size());
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new RuleParameterException(
                        name,
                        "Parameter '" + name + "' must only use text keys"
                );
            }
            if (entry.getValue() == null) {
                throw new RuleParameterException(
                        name,
                        "Parameter '" + name + "." + key + "' must not be null"
                );
            }
            group.put(key, entry.getValue());
        }
        return of(group);
    }

    private <V> V convert(String name, Object raw, Class<V> type) {
        if (type == String.class) {
            if (raw instanceof CharSequence text) {
                return type.cast(text.toString());
            }
            throw typeError(name, raw, "text");
        }
        if (type == Integer.class || type == int.class) {
            return uncheckedCast(toInt(name, raw));
        }
        if (type == Long.class || type == long.class) {
            return uncheckedCast(toLong(name, raw));
        }
        if (type == Double.class || type == double.class) {
            return uncheckedCast(toDecimal(name, raw).doubleValue());
        }
        if (type == BigDecimal.class) {
            return type.cast(toDecimal(name, raw));
        }
        if (type == Boolean.class || type == boolean.class) {
            return uncheckedCast(toBoolean(name, raw));
        }
        if (type.isEnum()) {
            return type.cast(toEnum(name, raw, type));
        }
        if (type.isInstance(raw)) {
            return type.cast(raw);
        }
        throw typeError(name, raw, "a " + type.getSimpleName());
    }

    private int toInt(String name, Object raw) {
        var decimal = toDecimal(name, raw);
        try {
            return decimal.intValueExact();
        } catch (ArithmeticException outOfRange) {
            throw new RuleParameterException(
                    name,
                    "Parameter '" + name + "' must be a whole number that fits in an int,"
                            + " but was " + decimal
            );
        }
    }

    private long toLong(String name, Object raw) {
        var decimal = toDecimal(name, raw);
        try {
            return decimal.longValueExact();
        } catch (ArithmeticException outOfRange) {
            throw new RuleParameterException(
                    name,
                    "Parameter '" + name + "' must be a whole number that fits in a long,"
                            + " but was " + decimal
            );
        }
    }

    private BigDecimal toDecimal(String name, Object raw) {
        if (raw instanceof BigDecimal decimal) {
            return decimal;
        }
        if (raw instanceof Number number) {
            return parseDecimal(name, number.toString());
        }
        if (raw instanceof CharSequence text) {
            return parseDecimal(name, text.toString());
        }
        throw typeError(name, raw, "a number");
    }

    private BigDecimal parseDecimal(String name, String text) {
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException notANumber) {
            throw new RuleParameterException(
                    name,
                    "Parameter '" + name + "' must be a number, but was '" + text + "'"
            );
        }
    }

    private boolean toBoolean(String name, Object raw) {
        if (raw instanceof Boolean value) {
            return value;
        }
        if (raw instanceof CharSequence text) {
            var normalized = text.toString().trim();
            if (normalized.equalsIgnoreCase("true")) {
                return true;
            }
            if (normalized.equalsIgnoreCase("false")) {
                return false;
            }
        }
        throw typeError(name, raw, "a boolean");
    }

    private Object toEnum(String name, Object raw, Class<?> type) {
        if (type.isInstance(raw)) {
            return raw;
        }
        if (raw instanceof CharSequence text) {
            var wanted = text.toString().trim();
            for (var constant : type.getEnumConstants()) {
                if (((Enum<?>) constant).name().equalsIgnoreCase(wanted)) {
                    return constant;
                }
            }
            throw new RuleParameterException(
                    name,
                    "Parameter '" + name + "' must be one of "
                            + List.of(type.getEnumConstants()) + ", but was '" + wanted + "'"
            );
        }
        throw typeError(name, raw, "a " + type.getSimpleName());
    }

    private RuleParameterException typeError(String name, Object raw, String expected) {
        return new RuleParameterException(
                name,
                "Parameter '" + name + "' must be " + expected + ", but was "
                        + raw.getClass().getSimpleName() + ": " + raw
        );
    }

    @SuppressWarnings("unchecked")
    private static <V> V uncheckedCast(Object value) {
        return (V) value;
    }
}
