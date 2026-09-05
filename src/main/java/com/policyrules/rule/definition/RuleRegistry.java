package com.policyrules.rule.definition;

import com.policyrules.rule.Rule;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Catalog of the rule types an application accepts in its configuration.
 *
 * <p>The registry is the security boundary of dynamic rules: configuration can
 * only combine and parameterize the types registered here, never introduce new
 * behavior. Anything not registered is rejected at compile time with an
 * {@link UnknownRuleTypeException}.</p>
 *
 * {@snippet lang = "java":
 * RuleRegistry<Customer> registry = new RuleRegistry<Customer>()
 *         .register("minimum-age", parameters -> {
 *             int minimum = parameters.intValue("minimum");
 *             return customer -> customer.age() >= minimum;
 *         })
 *         .register("country-in", parameters -> {
 *             var allowed = Set.copyOf(parameters.list("countries", String.class));
 *             return customer -> allowed.contains(customer.country());
 *         });
 *}
 *
 * <p>A registry is normally built once at start-up and shared afterwards. It is
 * thread-safe, and a type may be registered only once so an accidental
 * duplicate cannot silently replace behavior.</p>
 *
 * @param <T> context type consumed by the registered rules
 * @see RuleFactory
 * @see RuleCompiler
 */
public final class RuleRegistry<T> {

    /** Factories keyed by the type name used in the configuration. */
    private final Map<String, RuleFactory<T>> factories = new ConcurrentHashMap<>();

    /**
     * Creates an empty registry.
     */
    public RuleRegistry() {
    }

    /**
     * Registers the factory for a rule type.
     *
     * @param type    unique, non-blank name used in configuration
     * @param factory builds a rule from the parameters of that type
     * @return this registry, so registrations can be chained
     * @throws NullPointerException     if an argument is null
     * @throws IllegalArgumentException if the type is blank or already registered
     */
    public RuleRegistry<T> register(String type, RuleFactory<T> factory) {
        var validatedType = requireType(type);
        Objects.requireNonNull(factory, "factory must not be null");
        if (factories.putIfAbsent(validatedType, factory) != null) {
            throw new IllegalArgumentException("Rule type already registered: " + validatedType);
        }
        return this;
    }

    /**
     * Indicates whether a rule type is registered.
     *
     * <p>Useful to validate a stored configuration before it reaches production,
     * together with {@link RuleDefinitions#typesOf(RuleDefinition)}.</p>
     *
     * @param type type name to look up
     * @return {@code true} when a factory is registered for the type
     * @throws NullPointerException if {@code type} is null
     */
    public boolean contains(String type) {
        return factories.containsKey(Objects.requireNonNull(type, "type must not be null"));
    }

    /**
     * Returns every registered type name, sorted alphabetically.
     *
     * @return immutable snapshot of the registered type names
     */
    public Set<String> types() {
        return Collections.unmodifiableSet(new TreeSet<>(factories.keySet()));
    }

    /**
     * Builds the rule declared by an atomic definition.
     *
     * @param type       registered type name
     * @param parameters parameters loaded from the external source
     * @return the executable rule
     * @throws UnknownRuleTypeException if no factory is registered for the type
     * @throws RuleConfigurationException if the factory rejects the parameters
     *                                    or returns null
     * @throws NullPointerException     if an argument is null
     * @throws IllegalArgumentException if {@code type} is blank
     */
    public Rule<T> create(String type, Map<String, Object> parameters) {
        return create(type, RuleParameters.of(
                Objects.requireNonNull(parameters, "parameters must not be null")
        ));
    }

    /**
     * Builds the rule declared by an atomic definition.
     *
     * @param type       registered type name
     * @param parameters parameters loaded from the external source
     * @return the executable rule
     * @throws UnknownRuleTypeException if no factory is registered for the type
     * @throws RuleConfigurationException if the factory rejects the parameters
     *                                    or returns null
     * @throws NullPointerException     if an argument is null
     * @throws IllegalArgumentException if {@code type} is blank
     */
    public Rule<T> create(String type, RuleParameters parameters) {
        var validatedType = requireType(type);
        Objects.requireNonNull(parameters, "parameters must not be null");

        var factory = factories.get(validatedType);
        if (factory == null) {
            throw new UnknownRuleTypeException(validatedType, types());
        }

        var rule = factory.create(parameters);
        if (rule == null) {
            throw new RuleConfigurationException(
                    "Rule factory must not return null: " + validatedType
            );
        }
        return rule;
    }

    private static String requireType(String type) {
        Objects.requireNonNull(type, "type must not be null");
        if (type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        return type;
    }
}
